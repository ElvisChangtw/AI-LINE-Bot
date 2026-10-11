package com.elvischang.ailinebot.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// A blank apiKey turns YouTube watching off; the bot then sees only what web search shows of the page.
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String model,
        Duration maxClip,
        Duration requestTimeout,
        long inputMicroUsdPerMillion,
        long outputMicroUsdPerMillion,
        int maxOutputTokens,
        Duration summaryTtl,
        Duration failureTtl,
        Duration connectTimeout) {

    // A key pasted with a trailing newline would otherwise break the request header.
    public GeminiProperties {
        apiKey = apiKey == null ? null : apiKey.strip();
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
