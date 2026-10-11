package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.factcheck.SpendRepository;
import com.elvischang.ailinebot.factcheck.UsageGuard;
import com.elvischang.ailinebot.store.VideoSummaryRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("live")
class GeminiLiveSpikeTest {

    @Test
    void summarisesAPublicYouTubeVideo() {
        String key = System.getenv("GEMINI_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "GEMINI_API_KEY not set");
        VideoSummaryRepository summaries = mock(VideoSummaryRepository.class);
        when(summaries.find(anyString())).thenReturn(Optional.empty());
        UsageGuard guard = mock(UsageGuard.class);
        when(guard.allows(anyLong())).thenReturn(true);
        GeminiProperties props = new GeminiProperties(key, "https://generativelanguage.googleapis.com/v1beta",
                "gemini-3.8-flash", Duration.ofMinutes(10), Duration.ofSeconds(30), 750_000, 3_750_000, 8192,
                Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofSeconds(5));
        YouTubeWatcher watcher = new YouTubeWatcher(props, summaries, guard, mock(SpendRepository.class),
                java.time.Clock.systemUTC());

        Optional<String> summary = watcher.summary("dQw4w9WgXcQ");

        System.out.println("youtube summary=" + summary.orElse("(none)"));
        assertThat(summary).isPresent();
    }
}
