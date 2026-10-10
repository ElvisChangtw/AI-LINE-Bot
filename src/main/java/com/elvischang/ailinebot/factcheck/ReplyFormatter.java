package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ReplyFormatter {

    private static final int MAX_LENGTH = 500;
    private static final int MAX_ANSWER_LENGTH = 300;
    private static final int MAX_REASONS = 3;
    private static final int MAX_SOURCES = 2;
    private static final String BULLET = "・";
    private static final Pattern LINK = Pattern.compile("(https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);
    // Models sometimes inline citations as "([site](url))"; drop the whole thing, not just the url.
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\(?\\[[^\\]]*]\\([^)\\s]*\\)\\)?");
    private static final Map<Verdict, String> LABELS = new EnumMap<>(Map.of(
            Verdict.CORRECT, "內容正確",
            Verdict.INCORRECT, "這是假消息",
            Verdict.PARTLY_CORRECT, "部分正確",
            Verdict.OUTDATED_OR_MISSING_CONDITIONS, "過期了，或少了重要條件",
            Verdict.INSUFFICIENT_EVIDENCE, "查不到足夠資料，還不能確定",
            Verdict.OPINION, "這是看法，不是能查對錯的事"));

    private ReplyFormatter() {
    }

    public static String format(FactCheckResult result, boolean materialHasLink) {
        return switch (result.mode()) {
            case FACT_CHECK -> factCheck(result, materialHasLink);
            case ANSWER -> answer(result, materialHasLink);
            case NEED_MATERIAL -> BotReplies.ASK_TO_QUOTE;
        };
    }

    private static String answer(FactCheckResult result, boolean materialHasLink) {
        if (result.highRisk() && result.sources().isEmpty()) {
            return BotReplies.HIGH_RISK_NO_SOURCE;
        }
        String headline = withoutLinks(result.headline());
        List<String> points = new ArrayList<>(bullets(result.answerPoints()));
        if (points.isEmpty() && headline.isEmpty()) {
            points.add(BULLET + BotReplies.NOTHING_RELIABLE_FOUND);
        }
        List<String> notes = new ArrayList<>();
        if (result.safetyTip()) {
            notes.add(BotReplies.SAFETY_TIP);
        }
        if (materialHasLink) {
            notes.add(BotReplies.LINK_NOT_OPENED);
        }
        // A high-risk answer may only go out with the source that backs it.
        int minSources = result.highRisk() ? 1 : 0;
        return fit(headline, points, notes, sources(result), MAX_ANSWER_LENGTH, minSources);
    }

    private static List<String> bullets(List<String> modelTexts) {
        return modelTexts.stream()
                .map(ReplyFormatter::withoutLinks)
                .filter(text -> !text.isEmpty())
                .map(text -> BULLET + text)
                .limit(MAX_REASONS)
                .toList();
    }

    private static List<String> sources(FactCheckResult result) {
        return new ArrayList<>(result.sources().stream()
                .limit(MAX_SOURCES)
                .map(ReplyFormatter::sourceBlock)
                .toList());
    }

    private static String factCheck(FactCheckResult result, boolean materialHasLink) {
        String headline = withoutLinks(result.headline());
        String header = LABELS.get(result.overall()) + (headline.isEmpty() ? "" : "，" + headline);
        List<String> reasons = new ArrayList<>(bullets(result.claims().stream().map(FactCheckResult.Claim::reason).toList()));
        if (reasons.isEmpty() && result.overall() == Verdict.INSUFFICIENT_EVIDENCE) {
            reasons.add(BULLET + BotReplies.NOTHING_RELIABLE_FOUND);
        }
        List<String> notes = new ArrayList<>();
        if (result.safetyTip()) {
            notes.add(BotReplies.SAFETY_TIP);
        }
        if (materialHasLink) {
            notes.add(BotReplies.LINK_NOT_OPENED);
        }
        if (!result.political() && (result.overall() == Verdict.INCORRECT
                || result.overall() == Verdict.OUTDATED_OR_MISSING_CONDITIONS)) {
            notes.add(BotReplies.NO_FORWARD_NEEDED);
        }
        return fit(header, reasons, notes, sources(result), MAX_LENGTH, 0);
    }

    private static String fit(String header, List<String> lines, List<String> notes, List<String> sources,
                              int maxLength, int minSources) {
        String text = join(header, lines, notes, sources);
        while (text.length() > maxLength && sources.size() > minSources) {
            sources.remove(sources.size() - 1);
            text = join(header, lines, notes, sources);
        }
        while (text.length() > maxLength && lines.size() > 1) {
            lines.remove(lines.size() - 1);
            text = join(header, lines, notes, sources);
        }
        int keep = lines.isEmpty() ? 0 : lines.getFirst().length() - (text.length() - maxLength) - 1;
        if (text.length() > maxLength && minSources > 0 && keep > BULLET.length()) {
            lines.set(0, lines.getFirst().substring(0, keep) + "…");
            text = join(header, lines, notes, sources);
        }
        return text.length() > maxLength ? text.substring(0, maxLength - 1) + "…" : text;
    }

    private static String withoutLinks(String modelText) {
        return modelText == null ? "" : LINK.matcher(MARKDOWN_LINK.matcher(modelText).replaceAll("")).replaceAll("").strip();
    }

    private static String sourceBlock(FactCheckResult.Source source) {
        String publisher = withoutLinks(source.publisher());
        String name = publisher.isEmpty() ? host(source.url()) : publisher;
        String date = source.publishedDate() == null || source.publishedDate().isBlank() ? "" : "（" + source.publishedDate().trim() + "）";
        return "來源：" + name + date + "\n" + source.url();
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return url;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String join(String header, List<String> reasons, List<String> notes, List<String> sources) {
        List<String> lines = new ArrayList<>();
        if (!header.isEmpty()) {
            lines.add(header);
        }
        lines.addAll(reasons);
        lines.addAll(notes);
        lines.addAll(sources);
        return String.join("\n", lines);
    }
}
