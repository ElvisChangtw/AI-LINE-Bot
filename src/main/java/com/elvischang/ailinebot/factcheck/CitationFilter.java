package com.elvischang.ailinebot.factcheck;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class CitationFilter {

    private static final Set<String> TRACKING_PARAMS = Set.of("fbclid", "gclid", "mc_cid", "mc_eid");

    private CitationFilter() {
    }

    public static FactCheckResult apply(FactCheckResult result, Set<String> consultedUrls) {
        Set<String> consulted = consultedUrls.stream().map(CitationFilter::normalize).collect(Collectors.toSet());
        List<FactCheckResult.Source> kept = result.sources().stream()
                .filter(source -> consulted.contains(normalize(source.url())))
                .toList();
        if (kept.isEmpty() && result.overall() != Verdict.OPINION) {
            return result.downgradedToInsufficient();
        }
        return result.withSources(kept);
    }

    public static String normalize(String url) {
        String trimmed = url == null ? "" : url.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.getHost() == null) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = uri.getRawQuery() == null ? "" : Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(param -> !param.isBlank())
                    .filter(param -> {
                        String name = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
                        return !name.startsWith("utm_") && !TRACKING_PARAMS.contains(name);
                    })
                    .sorted()
                    .collect(Collectors.joining("&"));
            return host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (URISyntaxException e) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }
}
