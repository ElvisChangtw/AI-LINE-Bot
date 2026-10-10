package com.elvischang.ailinebot.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transcribe")
public record TranscribeProperties(
        String model,
        long microUsdPerMinute,
        Duration maxDuration,
        long maxBytes,
        long maxPreviewBytes,
        Duration requestTimeout) {
}
