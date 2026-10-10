package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.factcheck.UsageGuard;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linecorp.bot.messaging.model.GetMessageContentTranscodingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

// Voice and video are fetched only when someone asks, transcribed once, then kept as text like any other message.
@Component
public class MediaTranscriber {

    private static final Logger log = LoggerFactory.getLogger(MediaTranscriber.class);
    private static final long LINE_TIMEOUT_SECONDS = 60;
    private static final long MILLIS_PER_SECOND = 1000;
    private static final int LOCK_STRIPES = 64;
    private static final int NOT_FOUND = 404;
    private static final int GONE = 410;

    public sealed interface Prepared {
        record Ready(Material material) implements Prepared {
        }

        record NotReady() implements Prepared {
        }

        record TooLong() implements Prepared {
        }

        record Gone() implements Prepared {
        }

        record Unclear() implements Prepared {
        }

        record OverBudget() implements Prepared {
        }
    }

    private static final class ContentGone extends Exception {
    }

    private final MaterialRepository materials;
    private final MessagingApiBlobClient blobClient;
    private final MediaStore mediaStore;
    private final AudioTranscriber transcriber;
    private final VideoFrames frames;
    private final UsageGuard usageGuard;
    private final TranscribeProperties props;
    private final Clock clock;
    // Two people asking about the same clip at once must not pay for two transcriptions.
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public MediaTranscriber(MaterialRepository materials, MessagingApiBlobClient blobClient, MediaStore mediaStore,
                            AudioTranscriber transcriber, VideoFrames frames, UsageGuard usageGuard,
                            TranscribeProperties props, Clock clock) {
        this.materials = materials;
        this.blobClient = blobClient;
        this.mediaStore = mediaStore;
        this.transcriber = transcriber;
        this.frames = frames;
        this.usageGuard = usageGuard;
        this.props = props;
        this.clock = clock;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public Prepared prepare(Material media) {
        ReentrantLock lock = locks[Math.floorMod((media.groupId() + "/" + media.messageId()).hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            Optional<Material> current = materials.find(media.groupId(), media.messageId());
            return current.isEmpty() ? new Prepared.Gone() : prepareLocked(current.get());
        } finally {
            lock.unlock();
        }
    }

    private Prepared prepareLocked(Material media) {
        switch (media.mediaStatus()) {
            case STORED -> {
                return ready(media);
            }
            case TOO_LARGE -> {
                return new Prepared.TooLong();
            }
            case FAILED -> {
                return new Prepared.Gone();
            }
            default -> {
            }
        }
        if (!usageGuard.allows(clock.millis())) {
            return new Prepared.OverBudget();
        }
        String groupId = media.groupId();
        String messageId = media.messageId();
        try {
            GetMessageContentTranscodingResponse.Status status =
                    line(blobClient.getMessageContentTranscodingByMessageId(messageId)).status();
            if (status == GetMessageContentTranscodingResponse.Status.FAILED) {
                materials.markMedia(groupId, messageId, MediaStatus.FAILED, null);
                return new Prepared.Gone();
            }
            if (status != GetMessageContentTranscodingResponse.Status.SUCCEEDED) {
                return new Prepared.NotReady();
            }
            Path file = download(media);
            if (file == null) {
                materials.markMedia(groupId, messageId, MediaStatus.TOO_LARGE, null);
                return new Prepared.TooLong();
            }
            try {
                String transcript = transcriber.transcribe(file, expectedSeconds(media));
                // Saved before anything else can fail, so a retry never pays for the same clip twice.
                if (materials.saveTranscript(groupId, messageId, transcript, null) == 0) {
                    return new Prepared.Gone();
                }
                if (media.kind() == MaterialKind.VIDEO && !storeFrames(groupId, messageId, file)) {
                    storePreview(groupId, messageId);
                }
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (ContentGone e) {
            log.info("media content gone groupHash={} messageId={}", Hashes.shortHash(groupId), messageId);
            materials.markMedia(groupId, messageId, MediaStatus.FAILED, null);
            return new Prepared.Gone();
        } catch (IOException e) {
            throw new IllegalStateException("media scratch file failed", e);
        }
        return materials.find(groupId, messageId).<Prepared>map(this::ready).orElse(new Prepared.Gone());
    }

    private Prepared ready(Material media) {
        boolean noWords = media.text() == null || media.text().isBlank();
        boolean hasFrame = media.kind() == MaterialKind.VIDEO && media.mediaSha256() != null
                && mediaStore.storedFile(media.groupId(), media.messageId()).isPresent();
        return noWords && !hasFrame ? new Prepared.Unclear() : new Prepared.Ready(media);
    }

    // Null means the clip is bigger than OpenAI accepts.
    private Path download(Material media) throws ContentGone, IOException {
        BlobContent content = line(blobClient.getMessageContent(media.messageId()));
        String extension = media.kind() == MaterialKind.VIDEO ? "mp4" : "m4a";
        try {
            return mediaStore.scratch(content, extension, props.maxBytes());
        } catch (MediaTooLargeException e) {
            return null;
        }
    }

    // LINE always reports the length; if it ever did not, bill as if the clip were as long as allowed.
    private long expectedSeconds(Material media) {
        return materials.mediaDurationMs(media.groupId(), media.messageId())
                .orElse(props.maxDuration().toMillis()) / MILLIS_PER_SECOND;
    }

    // Frames only help; failing here must not undo or repeat the transcription.
    private boolean storeFrames(String groupId, String messageId, Path video) {
        long durationMs = materials.mediaDurationMs(groupId, messageId).orElse(0);
        Optional<Path> sheet = frames.contactSheet(video, durationMs);
        if (sheet.isEmpty()) {
            return false;
        }
        try {
            String sha = mediaStore.store(groupId, messageId, sheet.get(), props.maxPreviewBytes());
            if (materials.markMedia(groupId, messageId, MediaStatus.STORED, sha) == 0) {
                mediaStore.delete(groupId, messageId);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.info("video frames not stored groupHash={} messageId={} cause={}",
                    Hashes.shortHash(groupId), messageId, e.getClass().getSimpleName());
            return false;
        } finally {
            try {
                Files.deleteIfExists(sheet.get());
            } catch (IOException e) {
                log.info("video frames leftover cause={}", e.getClass().getSimpleName());
            }
        }
    }

    // LINE's single preview image, used only when frames could not be made.
    private void storePreview(String groupId, String messageId) {
        try {
            BlobContent preview = line(blobClient.getMessageContentPreview(messageId));
            String sha = mediaStore.store(groupId, messageId, preview, props.maxPreviewBytes());
            if (materials.markMedia(groupId, messageId, MediaStatus.STORED, sha) == 0) {
                mediaStore.delete(groupId, messageId);
            }
        } catch (Exception e) {
            log.info("video preview unavailable groupHash={} messageId={} cause={}",
                    Hashes.shortHash(groupId), messageId, e.getClass().getSimpleName());
        }
    }

    // 404/410 mean LINE no longer has the content; anything else may be temporary, so the job retries.
    private <T> T line(CompletableFuture<Result<T>> call) throws ContentGone {
        try {
            return call.get(LINE_TIMEOUT_SECONDS, TimeUnit.SECONDS).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling LINE", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AbstractLineClientException error
                    && (error.getCode() == NOT_FOUND || error.getCode() == GONE)) {
                throw new ContentGone();
            }
            throw new IllegalStateException("LINE content request failed", e);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new IllegalStateException("LINE content request timed out", e);
        }
    }
}
