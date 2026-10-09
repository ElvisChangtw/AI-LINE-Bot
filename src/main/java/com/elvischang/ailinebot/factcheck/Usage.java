package com.elvischang.ailinebot.factcheck;

public record Usage(long inputTokens, long outputTokens, int searchCalls) {

    private static final long MILLION = 1_000_000L;

    public static final Usage NONE = new Usage(0, 0, 0);

    public long costMicroUsd(FactCheckProperties p) {
        return ceilDiv(inputTokens * p.inputMicroUsdPerMillion())
                + ceilDiv(outputTokens * p.outputMicroUsdPerMillion())
                + searchCalls * p.searchMicroUsdPerCall();
    }

    private static long ceilDiv(long value) {
        return Math.ceilDiv(value, MILLION);
    }
}
