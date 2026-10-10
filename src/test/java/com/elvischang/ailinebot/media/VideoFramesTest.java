package com.elvischang.ailinebot.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VideoFramesTest {

    private final VideoFrames frames = new VideoFrames("ffmpeg", Duration.ofSeconds(30));

    private static boolean ffmpegInstalled() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void turnsAVideoIntoOneJpegOfFramesFromStartToEnd(@TempDir Path dir) throws Exception {
        assumeTrue(ffmpegInstalled(), "ffmpeg not installed");
        Path video = dir.resolve("clip.mp4");
        Files.copy(Path.of(getClass().getResource("/video/tiny.mp4").toURI()), video);

        Path sheet = frames.contactSheet(video, 3_000).orElseThrow();

        byte[] jpeg = Files.readAllBytes(sheet);
        assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
        assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void aFileThatIsNotAVideoGivesNoFramesInsteadOfFailing(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("junk.mp4");
        Files.write(junk, new byte[] {4, 5, 6});

        assertThat(frames.contactSheet(junk, 3_000)).isEmpty();
        assertThat(new VideoFrames("/no/such/ffmpeg", Duration.ofSeconds(5)).contactSheet(junk, 3_000)).isEmpty();
    }
}
