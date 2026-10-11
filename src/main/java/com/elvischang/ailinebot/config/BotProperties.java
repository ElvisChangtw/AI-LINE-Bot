package com.elvischang.ailinebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Duration replyDeadline,
        Duration replyTimeout,
        Duration materialTtl,
        Duration waitingTtl,
        Duration recentWindow,
        Duration jobLease,
        int jobMaxAttempts,
        Duration staleAfter,
        Path mediaDir,
        long mediaMaxBytes,
        int mediaMaxAttempts,
        Duration mediaDownloadTimeout,
        Duration jobRetention,
        int maxMessagesPerReply,
        int conversationMessages) {
}
