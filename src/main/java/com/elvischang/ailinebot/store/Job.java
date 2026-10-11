package com.elvischang.ailinebot.store;

public record Job(
        String id,
        JobKind kind,
        String groupId,
        String triggerMessageId,
        String materialMessageId,
        String userQuestion,
        String replyToken,
        String quoteToken,
        long receivedAt,
        JobStatus status,
        int attempts,
        Long leaseUntil,
        String resultText,
        SendState sendState,
        String carrierJobId,
        Long waitingSince,
        String errorCode,
        long createdAt,
        Long finishedAt,
        String about) {
}
