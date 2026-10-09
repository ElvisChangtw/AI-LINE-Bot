package com.elvischang.ailinebot.factcheck;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "factcheck")
public record FactCheckProperties(
        String baseUrl,
        String apiKey,
        String model,
        String reasoningEffort,
        int maxToolCalls,
        Duration requestTimeout,
        long inputMicroUsdPerMillion,
        long outputMicroUsdPerMillion,
        long searchMicroUsdPerCall,
        long dailyLimitMicroUsd,
        long monthlyLimitMicroUsd,
        Duration cacheTtl,
        String promptVersion) {
}
