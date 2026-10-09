package com.elvischang.ailinebot.store;

public record NewJob(
        JobKind kind,
        String groupId,
        String triggerMessageId,
        String materialMessageId,
        String userQuestion,
        String replyToken,
        String quoteToken,
        String resultText) {
}
