package com.elvischang.ailinebot.factcheck;

public record FactCheckReply(String text, Verdict verdict, Usage usage, long costMicroUsd) {

    public static FactCheckReply notice(String text) {
        return new FactCheckReply(text, null, Usage.NONE, 0);
    }
}
