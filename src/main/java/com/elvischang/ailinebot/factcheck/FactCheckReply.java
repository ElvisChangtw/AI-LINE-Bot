package com.elvischang.ailinebot.factcheck;

public record FactCheckReply(String text, Verdict verdict, ReplyMode mode, Usage usage, long costMicroUsd,
                             Integer targetMessage) {

    public FactCheckReply(String text, Verdict verdict, ReplyMode mode, Usage usage, long costMicroUsd) {
        this(text, verdict, mode, usage, costMicroUsd, null);
    }

    public static FactCheckReply notice(String text) {
        return new FactCheckReply(text, null, null, Usage.NONE, 0);
    }
}
