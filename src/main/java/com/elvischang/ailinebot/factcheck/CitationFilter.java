package com.elvischang.ailinebot.factcheck;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        Set<String> consulted = consultedUrls.stream()
                .filter(CitationFilter::isPresent)
                .map(CitationFilter::normalize)
                .collect(Collectors.toSet());
        List<FactCheckResult.Source> kept = result.sources().stream()
                .filter(source -> isPresent(source.url()))
                .filter(source -> consulted.contains(normalize(source.url())))
                .toList();
        // Answers without sources are handled by ReplyFormatter according to their risk.
        // An insufficient answer claims nothing, so its reason (e.g. the video could not be watched) must survive.
        if (kept.isEmpty() && result.mode() == ReplyMode.FACT_CHECK && result.overall() != Verdict.OPINION
                && result.overall() != Verdict.INSUFFICIENT_EVIDENCE) {
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
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = uri.getRawQuery() == null ? "" : Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(param -> !param.isBlank())
                    .map(CitationFilter::decodePair)
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

    private static boolean isPresent(String url) {
        return url != null && !url.isBlank();
    }

    private static String decodePair(String pair) {
        String[] parts = pair.split("=", 2);
        try {
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            return parts.length == 1 ? name : name + "=" + URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return pair;
        }
    }
}
