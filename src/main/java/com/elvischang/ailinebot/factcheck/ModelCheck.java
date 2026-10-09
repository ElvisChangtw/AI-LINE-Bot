package com.elvischang.ailinebot.factcheck;

import java.util.Set;

public record ModelCheck(FactCheckResult result, Set<String> consultedUrls, Usage usage) {
}
