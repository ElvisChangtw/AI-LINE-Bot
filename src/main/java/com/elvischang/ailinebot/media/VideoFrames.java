package com.elvischang.ailinebot.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// Six frames spread over the clip, tiled 3x2 into one JPEG, so the model sees the whole video (captions included).
@Component
public class VideoFrames {

    private static final Logger log = LoggerFactory.getLogger(VideoFrames.class);
    private static final int FRAMES = 6;
    private static final double FALLBACK_SECONDS = 30;
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final String ffmpeg;
    private final Duration timeout;

    @Autowired
    public VideoFrames(TranscribeProperties props) {
        this(props.ffmpegPath(), props.frameTimeout());
    }

    VideoFrames(String ffmpeg, Duration timeout) {
        this.ffmpeg = ffmpeg;
        this.timeout = timeout;
    }

    // Empty when ffmpeg is missing, the file is not a video, or it takes too long; the caller falls back.
    public Optional<Path> contactSheet(Path video, long durationMs) {
        Path sheet = video.resolveSibling(video.getFileName() + "-frames.jpg");
        double seconds = durationMs > 0 ? durationMs / MILLIS_PER_SECOND : FALLBACK_SECONDS;
        String filter = String.format(Locale.ROOT, "fps=%d/%.3f,scale=480:-2,tile=3x2:padding=4:color=white", FRAMES, seconds);
        // Untrusted input: no shell, local files only, no stdin, bounded time.
        List<String> command = List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file", "-y", "-i", video.toString(),
                "-vf", filter, "-frames:v", "1", "-q:v", "5", sheet.toString());
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.info("video frames timed out");
                return discard(sheet);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(sheet) || Files.size(sheet) == 0) {
                log.info("video frames failed exit={}", process.exitValue());
                return discard(sheet);
            }
            return Optional.of(sheet);
        } catch (IOException e) {
            log.info("video frames unavailable cause={}", e.getClass().getSimpleName());
            return discard(sheet);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return discard(sheet);
        }
    }

    private static Optional<Path> discard(Path sheet) {
        try {
            Files.deleteIfExists(sheet);
        } catch (IOException e) {
            log.info("video frames leftover cause={}", e.getClass().getSimpleName());
        }
        return Optional.empty();
    }
}
