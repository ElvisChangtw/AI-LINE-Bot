package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.factcheck.SpendRepository;
import com.elvischang.ailinebot.factcheck.UsageGuard;
import com.elvischang.ailinebot.store.VideoSummaryRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouTubeWatcherTest {

    private static final String PATH = "/models/gemini-test:generateContent";
    private static final long NOW = 1_000_000_000L;
    private static final long TTL_START = NOW - Duration.ofDays(7).toMillis();

    private final WireMockServer gemini = new WireMockServer(options().dynamicPort());
    private final VideoSummaryRepository summaries = mock(VideoSummaryRepository.class);
    private final UsageGuard usageGuard = mock(UsageGuard.class);
    private final SpendRepository spending = mock(SpendRepository.class);
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @BeforeEach
    void start() {
        gemini.start();
        when(usageGuard.allows(anyLong())).thenReturn(true);
        when(summaries.find(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void stop() {
        gemini.stop();
    }

    private YouTubeWatcher watcher(String apiKey) {
        GeminiProperties props = new GeminiProperties(apiKey, gemini.baseUrl(), "gemini-test", Duration.ofMinutes(10),
                Duration.ofSeconds(1), 300_000, 2_500_000, 4096, Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofSeconds(1));
        return new YouTubeWatcher(props, summaries, usageGuard, spending, clock);
    }

    @Test
    void asksGeminiToWatchTheFirstTenMinutesAndKeepsTheSummary() {
        gemini.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"想一想","thought":true},{"text":"講者說"},{"text":"檸檬水治癌"}]}}],
                 "usageMetadata":{"promptTokenCount":60000,"candidatesTokenCount":800,"thoughtsTokenCount":200}}""")));

        Optional<String> summary = watcher(" k\n").summary("dQw4w9WgXcQ");

        assertThat(summary).contains("講者說檸檬水治癌");
        gemini.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-goog-api-key", equalTo("k"))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].fileData.fileUri",
                        equalTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].videoMetadata.endOffset", equalTo("600s")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[1].text", equalTo(YouTubeWatcher.INSTRUCTION)))
                .withRequestBody(matchingJsonPath("$.generationConfig.mediaResolution", equalTo("MEDIA_RESOLUTION_LOW"))));
        verify(spending).record(NOW, 60_000L * 300_000 / 1_000_000 + 1_000L * 2_500_000 / 1_000_000);
        verify(summaries).save("dQw4w9WgXcQ", "講者說檸檬水治癌", NOW, TTL_START);
    }

    @Test
    void reusesWhatItKnowsAndRetriesOnlyAfterItExpires() {
        long thirtyOneMinutesAgo = NOW - Duration.ofMinutes(31).toMillis();
        when(summaries.find("aaaaaaaaaaa")).thenReturn(Optional.of(new VideoSummaryRepository.Saved("之前看過", TTL_START)));
        when(summaries.find("bbbbbbbbbbb")).thenReturn(Optional.of(new VideoSummaryRepository.Saved("", NOW - 1)));
        when(summaries.find("ccccccccccc")).thenReturn(Optional.of(new VideoSummaryRepository.Saved("", thirtyOneMinutesAgo)));
        when(summaries.find("ddddddddddd")).thenReturn(Optional.of(new VideoSummaryRepository.Saved("太舊", TTL_START - 1)));
        gemini.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(403)));

        assertThat(watcher("k").summary("aaaaaaaaaaa")).contains("之前看過");
        assertThat(watcher("k").summary("bbbbbbbbbbb")).isEmpty();
        assertThat(watcher("k").summary("ccccccccccc")).isEmpty();
        assertThat(watcher("k").summary("ddddddddddd")).isEmpty();

        assertThat(gemini.getAllServeEvents()).hasSize(2);
        verify(summaries).save("ccccccccccc", "", NOW, TTL_START);
    }

    @Test
    void anOverloadedGeminiIsNotRememberedSoTheNextAskWatchesAgain() {
        gemini.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThat(watcher("k").summary("dQw4w9WgXcQ")).isEmpty();

        verify(summaries, never()).save(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void staysOffOverBudgetOrWhenGeminiCannotWatch() {
        assertThat(watcher(" ").summary("dQw4w9WgXcQ")).isEmpty();
        assertThat(gemini.getAllServeEvents()).isEmpty();

        gemini.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400).withBody("{\"error\":{}}")));
        assertThat(watcher("k").summary("dQw4w9WgXcQ")).isEmpty();
        gemini.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(
                "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"講到一半\"}]}}]}")));
        assertThat(watcher("k").summary("dQw4w9WgXcQ")).isEmpty();
        gemini.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        assertThat(watcher("k").summary("dQw4w9WgXcQ")).isEmpty();
        gemini.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{}").withFixedDelay(2_000)));
        assertThat(watcher("k").summary("dQw4w9WgXcQ")).as("request timeout").isEmpty();
        when(summaries.find(anyString())).thenThrow(new IllegalStateException("database locked"));
        assertThat(watcher("k").summary("dQw4w9WgXcQ")).isEmpty();
        when(usageGuard.allows(anyLong())).thenReturn(false);
        doReturn(Optional.empty()).when(summaries).find(anyString());
        assertThat(watcher("k").summary("dQw4w9WgXcQ")).isEmpty();

        assertThat(gemini.getAllServeEvents()).hasSize(4);
        verify(summaries, times(3)).save("dQw4w9WgXcQ", "", NOW, TTL_START);
        long estimate = 600L * 100 * 300_000 / 1_000_000 + 4096L * 2_500_000 / 1_000_000;
        verify(spending, times(2)).record(NOW, estimate);
    }
}
