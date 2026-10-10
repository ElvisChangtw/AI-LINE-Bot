package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MediaStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DefaultFactCheckService implements FactCheckService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFactCheckService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern LINK = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final FactChecker checker;
    private final VerificationCacheRepository cache;
    private final UsageGuard usageGuard;
    private final MediaStore mediaStore;
    private final FactCheckProperties props;
    private final Clock clock;

    public DefaultFactCheckService(FactChecker checker, VerificationCacheRepository cache, UsageGuard usageGuard,
                                   MediaStore mediaStore, FactCheckProperties props, Clock clock) {
        this.checker = checker;
        this.cache = cache;
        this.usageGuard = usageGuard;
        this.mediaStore = mediaStore;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public FactCheckReply verify(String groupId, Material material, String userQuestion) {
        if (material == null) {
            return check(groupId, null, null, null, userQuestion);
        }
        if (material.kind() == MaterialKind.IMAGE && material.mediaStatus() != MediaStatus.STORED) {
            return FactCheckReply.notice(material.mediaStatus() == MediaStatus.PENDING
                    ? BotReplies.IMAGE_NOT_READY : BotReplies.IMAGE_GONE);
        }
        // Without a hash every such image would share one cache key.
        if (material.kind() == MaterialKind.IMAGE && (material.mediaSha256() == null || material.mediaSha256().isBlank())) {
            return FactCheckReply.notice(BotReplies.IMAGE_GONE);
        }
        String text = material.kind() == MaterialKind.IMAGE ? null : material.text();
        byte[] image = material.kind() == MaterialKind.IMAGE ? readImage(material) : null;
        if (material.kind() == MaterialKind.IMAGE && image == null) {
            return FactCheckReply.notice(BotReplies.IMAGE_GONE);
        }
        return check(groupId, text, image, material.mediaSha256(), userQuestion);
    }

    private FactCheckReply check(String groupId, String text, byte[] image, String imageSha256, String userQuestion) {
        boolean hasLink = (text != null && LINK.matcher(text).find())
                || (userQuestion != null && LINK.matcher(userQuestion).find());
        long now = clock.millis();
        String key = cacheKey(userQuestion, text, imageSha256);

        Optional<FactCheckResult> cached = cache.get(key, now);
        if (cached.isPresent()) {
            logOutcome(groupId, true, cached.get(), Usage.NONE, 0);
            return reply(cached.get(), hasLink, Usage.NONE, 0);
        }
        if (!usageGuard.allows(now)) {
            log.info("factcheck refused groupHash={} reason=budget", Hashes.shortHash(groupId));
            return FactCheckReply.notice(BotReplies.QUOTA_EXCEEDED);
        }
        ModelCheck check;
        try {
            check = checker.check(new FactCheckInput(text, image, image == null ? null : mimeType(image),
                    userQuestion, LocalDate.now(clock.withZone(TAIPEI))));
        } catch (OutOfCreditException e) {
            log.warn("factcheck refused groupHash={} reason=out_of_credit", Hashes.shortHash(groupId));
            return FactCheckReply.notice(BotReplies.OUT_OF_CREDIT);
        }
        if (check.result().mode() == ReplyMode.NEED_MATERIAL && (text != null || image != null)) {
            throw new InvalidModelOutputException("asked for material that was already sent");
        }
        FactCheckResult filtered = CitationFilter.apply(check.result(), check.consultedUrls());
        // Answers go stale (weather, news) and new rumours often get fact-checked hours later, so neither may stick.
        if (filtered.mode() == ReplyMode.FACT_CHECK && filtered.overall() != Verdict.INSUFFICIENT_EVIDENCE) {
            cache.put(key, filtered, now);
        }
        long cost = check.usage().costMicroUsd(props);
        logOutcome(groupId, false, filtered, check.usage(), cost);
        return reply(filtered, hasLink, check.usage(), cost);
    }

    private static FactCheckReply reply(FactCheckResult result, boolean hasLink, Usage usage, long cost) {
        Verdict verdict = result.mode() == ReplyMode.FACT_CHECK ? result.overall() : null;
        return new FactCheckReply(nonBlank(ReplyFormatter.format(result, hasLink)), verdict, result.mode(), usage, cost);
    }

    private byte[] readImage(Material material) {
        Optional<Path> file = mediaStore.storedFile(material.groupId(), material.messageId());
        if (file.isEmpty()) {
            return null;
        }
        try {
            return Files.readAllBytes(file.get());
        } catch (IOException e) {
            throw new UncheckedIOException("stored media unreadable", e);
        }
    }

    private String cacheKey(String question, String text, String imageSha256) {
        String raw = props.promptVersion() + "\n" + props.model() + "\n" + normalized(question) + "\n" + normalized(text) + "\n"
                + (imageSha256 == null ? "" : imageSha256);
        return Hashes.sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    // Collapsing whitespace also removes newlines, so the "\n"-joined key stays unambiguous.
    private static String normalized(String value) {
        return value == null ? "" : WHITESPACE.matcher(value).replaceAll(" ").trim();
    }

    static String mimeType(byte[] image) {
        if (image.length >= 4 && (image[0] & 0xFF) == 0x89 && image[1] == 0x50 && image[2] == 0x4E && image[3] == 0x47) {
            return "image/png";
        }
        if (image.length >= 3 && image[0] == 0x47 && image[1] == 0x49 && image[2] == 0x46) {
            return "image/gif";
        }
        if (image.length >= 12 && image[0] == 'R' && image[1] == 'I' && image[2] == 'F' && image[3] == 'F'
                && image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static String nonBlank(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("formatter produced an empty reply");
        }
        return text;
    }

    private void logOutcome(String groupId, boolean cacheHit, FactCheckResult result, Usage usage, long cost) {
        log.info("factcheck groupHash={} cacheHit={} mode={} verdict={} highRisk={} sources={} inputTokens={} outputTokens={} searchCalls={} costMicroUsd={}",
                Hashes.shortHash(groupId), cacheHit, result.mode(), result.overall(), result.highRisk(), result.sources().size(),
                usage.inputTokens(), usage.outputTokens(), usage.searchCalls(), cost);
    }
}
