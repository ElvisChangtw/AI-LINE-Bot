package com.elvischang.ailinebot.factcheck;

import java.util.Set;

// targetMessage is the number of the recent chat message the model asked to check, when it asked for one.
public record ModelCheck(FactCheckResult result, Set<String> consultedUrls, Usage usage, Integer targetMessage) {

    public ModelCheck(FactCheckResult result, Set<String> consultedUrls, Usage usage) {
        this(result, consultedUrls, usage, null);
    }
}
