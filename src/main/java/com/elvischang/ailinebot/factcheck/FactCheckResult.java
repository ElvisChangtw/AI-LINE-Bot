package com.elvischang.ailinebot.factcheck;

import java.util.List;

public record FactCheckResult(
        Verdict overall,
        boolean political,
        String headline,
        List<Claim> claims,
        boolean safetyTip,
        List<Source> sources,
        ReplyMode mode,
        boolean highRisk,
        List<String> answerPoints) {

    public record Claim(String text, Verdict verdict, String reason) {
    }

    public record Source(String url, String title, String publisher, String publishedDate) {
    }

    public static FactCheckResult factCheck(Verdict overall, boolean political, String headline, List<Claim> claims,
                                            boolean safetyTip, List<Source> sources) {
        return new FactCheckResult(overall, political, headline, claims, safetyTip, sources, ReplyMode.FACT_CHECK, false, List.of());
    }

    public FactCheckResult withSources(List<Source> kept) {
        return new FactCheckResult(overall, political, headline, claims, safetyTip, List.copyOf(kept), mode, highRisk, answerPoints);
    }

    public FactCheckResult downgradedToInsufficient() {
        return new FactCheckResult(Verdict.INSUFFICIENT_EVIDENCE, political, "", List.of(), safetyTip, List.of(), mode, highRisk, List.of());
    }
}
