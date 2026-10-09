package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class MediaStore {

    private static final Logger log = LoggerFactory.getLogger(MediaStore.class);
    private static final Pattern MESSAGE_ID = Pattern.compile("^[0-9]{1,32}$");
    private static final int BUFFER_SIZE = 8192;

    private final MaterialRepository materials;
    private final MessagingApiBlobClient blobClient;
    private final BotProperties props;

    public MediaStore(MaterialRepository materials, MessagingApiBlobClient blobClient, BotProperties props) {
        this.materials = materials;
        this.blobClient = blobClient;
        this.props = props;
    }

    public void download(String groupId, String messageId) {
        Optional<Material> material = materials.find(groupId, messageId);
        if (material.isEmpty() || material.get().mediaStatus() != MediaStatus.PENDING) {
            return;
        }
        Path target = pathFor(groupId, messageId);
        try {
            CompletableFuture<Result<BlobContent>> future = blobClient.getMessageContent(messageId);
            BlobContent content;
            try {
                content = future.get(props.mediaDownloadTimeout().toMillis(), TimeUnit.MILLISECONDS).body();
            } catch (TimeoutException e) {
                future.cancel(true);
                throw e;
            }
            String sha256 = writeLimited(content, target);
            materials.markMedia(groupId, messageId, MediaStatus.STORED, sha256);
        } catch (MediaTooLargeException e) {
            materials.markMedia(groupId, messageId, MediaStatus.TOO_LARGE, null);
            log.info("media too large groupHash={} messageId={}", Hashes.shortHash(groupId), messageId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            OptionalInt attempts = materials.recordMediaFailure(groupId, messageId, props.mediaMaxAttempts());
            log.warn("media download failed groupHash={} messageId={} attempts={} cause={}",
                    Hashes.shortHash(groupId), messageId,
                    attempts.isPresent() ? attempts.getAsInt() : "n/a", e.getClass().getSimpleName());
        }
    }

    public Optional<Path> storedFile(String groupId, String messageId) {
        Path path = pathFor(groupId, messageId);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    public void delete(String groupId, String messageId) {
        try {
            Files.deleteIfExists(pathFor(groupId, messageId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path pathFor(String groupId, String messageId) {
        if (messageId == null || !MESSAGE_ID.matcher(messageId).matches()) {
            throw new IllegalArgumentException("invalid LINE message id");
        }
        return props.mediaDir().resolve(Hashes.shortHash(groupId)).resolve(messageId);
    }

    private String writeLimited(BlobContent content, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "download-", ".part");
        try (InputStream in = content.byteStream();
             DigestOutputStream out = new DigestOutputStream(Files.newOutputStream(temp), sha256())) {
            long total = copyLimited(in, out);
            if (total == 0) {
                throw new IOException("empty media content");
            }
            out.flush();
            String hash = HexFormat.of().formatHex(out.getMessageDigest().digest());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return hash;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private long copyLimited(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > props.mediaMaxBytes()) {
                throw new MediaTooLargeException(props.mediaMaxBytes());
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
