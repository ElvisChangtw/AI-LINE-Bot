package com.elvischang.ailinebot.store;

public record Material(
        String groupId,
        String messageId,
        MaterialKind kind,
        String text,
        boolean botTrigger,
        MediaStatus mediaStatus,
        String mediaSha256,
        int mediaAttempts,
        long createdAt,
        long expiresAt) {
}
