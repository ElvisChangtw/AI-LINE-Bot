package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.media.YouTubeLinks;
import com.elvischang.ailinebot.media.YouTubeWatcher;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;

@Service
public class DefaultFactCheckService implements FactCheckService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFactCheckService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"'）)」]+", Pattern.CASE_INSENSITIVE);
    // Speech-to-text may mishear and often comes back in simplified characters; the prompt explains both.
    static final String VOICE_LABEL = "（語音訊息轉成的文字）\n";
    static final String VIDEO_LABEL = "（影片的語音轉成的文字，圖片是影片裡的畫面，通常是從頭到尾平均取的 6 個畫面拼成一張）\n";
    static final String VIDEO_NO_FRAME_LABEL = "（影片的語音轉成的文字）\n";
    static final String UNWATCHED_YOUTUBE = "（這支 YouTube 影片沒辦法看到內容，請用網址搜尋影片標題、說明和相關新聞來查證）";
    static final String VIDEO_FRAME_ONLY_LABEL = "（影片裡沒有聽到說話，只附影片裡的畫面，通常是從頭到尾平均取的 6 個畫面拼成一張）\n";
    private static final Pattern SCHEME_AND_WWW = Pattern.compile("^(https?://)?(www\\.)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final FactChecker checker;
    private final VerificationCacheRepository cache;
    private final UsageGuard usageGuard;
    private final MediaStore mediaStore;
    private final YouTubeWatcher youTube;
    private final FactCheckProperties props;
    private final Clock clock;

    public DefaultFactCheckService(FactChecker checker, VerificationCacheRepository cache, UsageGuard usageGuard,
                                   MediaStore mediaStore, YouTubeWatcher youTube, FactCheckProperties props, Clock clock) {
        this.checker = checker;
        this.cache = cache;
        this.usageGuard = usageGuard;
        this.mediaStore = mediaStore;
        this.youTube = youTube;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public FactCheckReply verify(String groupId, Material material, String userQuestion, String conversation) {
        if (material == null) {
            return check(groupId, null, null, null, userQuestion, conversation);
        }
        if (material.kind() == MaterialKind.IMAGE && material.mediaStatus() != MediaStatus.STORED) {
            return FactCheckReply.notice(material.mediaStatus() == MediaStatus.PENDING
                    ? BotReplies.IMAGE_NOT_READY : BotReplies.IMAGE_GONE);
        }
        // Without a hash every such image would share one cache key.
        if (material.kind() == MaterialKind.IMAGE && (material.mediaSha256() == null || material.mediaSha256().isBlank())) {
            return FactCheckReply.notice(BotReplies.IMAGE_GONE);
        }
        if (material.kind() == MaterialKind.AUDIO || material.kind() == MaterialKind.VIDEO) {
            return checkMedia(groupId, material, userQuestion, conversation);
        }
        String text = material.kind() == MaterialKind.IMAGE ? null : material.text();
        byte[] image = material.kind() == MaterialKind.IMAGE ? readImage(material) : null;
        if (material.kind() == MaterialKind.IMAGE && image == null) {
            return FactCheckReply.notice(BotReplies.IMAGE_GONE);
        }
        return check(groupId, text, image, material.mediaSha256(), userQuestion, conversation);
    }

    // Callers transcribe first (MediaTranscriber); a video also brings its preview frame when LINE had one.
    private FactCheckReply checkMedia(String groupId, Material media, String userQuestion, String conversation) {
        String words = media.text() == null ? "" : media.text();
        if (media.kind() == MaterialKind.AUDIO) {
            return check(groupId, VOICE_LABEL + words, null, null, userQuestion, conversation);
        }
        byte[] frame = media.mediaSha256() == null ? null : readImage(media);
        String label = frame == null ? VIDEO_NO_FRAME_LABEL : words.isBlank() ? VIDEO_FRAME_ONLY_LABEL : VIDEO_LABEL;
        return check(groupId, label + words, frame, frame == null ? null : media.mediaSha256(), userQuestion, conversation);
    }

    private FactCheckReply check(String groupId, String material, byte[] image, String imageSha256, String userQuestion,
                                 String conversation) {
        boolean hasMaterial = material != null || image != null;
        // With material we check its video, so a link in the question cannot pull in an unrelated one.
        Optional<String> videoId = material != null ? YouTubeLinks.firstVideoId(material) : YouTubeLinks.firstVideoId(userQuestion);
        Optional<String> watched = videoId.flatMap(youTube::summary);
        String text = watched.map(s -> (material == null ? "" : material + "\n\n") + youTube.label() + s)
                .orElse(videoId.isPresent() && material != null ? material + "\n\n" + UNWATCHED_YOUTUBE : material);
        // The watched video counts as opened even though web search never fetched the page; other links still count.
        List<String> links = links(material, userQuestion).stream()
                .filter(link -> watched.isEmpty() || !YouTubeLinks.videoIdOf(link).equals(videoId))
                .toList();
        long now = clock.millis();
        String key = cacheKey(userQuestion, text, imageSha256);
        // An answer that saw the chat may quote it or lean on it, so it must not be served to anyone else.
        boolean cacheable = conversation == null || conversation.isBlank();

        Optional<FactCheckResult> cached = cacheable ? cache.get(key, now) : Optional.empty();
        if (cached.isPresent()) {
            logOutcome(groupId, true, cached.get(), Usage.NONE, 0);
            boolean unopened = noneOpened(links, cached.get().sources().stream().map(FactCheckResult.Source::url).toList());
            return reply(cached.get(), unopened, Usage.NONE, 0);
        }
        if (!usageGuard.allows(now)) {
            log.info("factcheck refused groupHash={} reason=budget", Hashes.shortHash(groupId));
            return FactCheckReply.notice(BotReplies.QUOTA_EXCEEDED);
        }
        ModelCheck check;
        try {
            check = checker.check(new FactCheckInput(text, image, image == null ? null : mimeType(image),
                    userQuestion, LocalDate.now(clock.withZone(TAIPEI)), conversation));
        } catch (OutOfCreditException e) {
            log.warn("factcheck refused groupHash={} reason=out_of_credit", Hashes.shortHash(groupId));
            return FactCheckReply.notice(BotReplies.OUT_OF_CREDIT);
        }
        // The model had the message but could not use it (a video it cannot watch); asking to quote it again would loop.
        if (check.result().mode() == ReplyMode.NEED_MATERIAL && hasMaterial) {
            log.info("factcheck groupHash={} material unusable", Hashes.shortHash(groupId));
            return FactCheckReply.notice(BotReplies.CANNOT_SEE_MATERIAL);
        }
        FactCheckResult filtered = CitationFilter.apply(check.result(), check.consultedUrls());
        // Answers go stale (weather, news) and new rumours often get fact-checked hours later, so neither may stick.
        if (cacheable && filtered.mode() == ReplyMode.FACT_CHECK && filtered.overall() != Verdict.INSUFFICIENT_EVIDENCE) {
            cache.put(key, filtered, now);
        }
        long cost = check.usage().costMicroUsd(props);
        logOutcome(groupId, false, filtered, check.usage(), cost);
        FactCheckReply reply = reply(filtered, noneOpened(links, check.consultedUrls()), check.usage(), cost);
        return filtered.mode() == ReplyMode.NEED_MATERIAL ? withTarget(reply, check.targetMessage()) : reply;
    }

    private static FactCheckReply withTarget(FactCheckReply reply, Integer targetMessage) {
        return new FactCheckReply(reply.text(), reply.verdict(), reply.mode(), reply.usage(), reply.costMicroUsd(), targetMessage);
    }

    private static List<String> links(String text, String userQuestion) {
        return Stream.of(text, userQuestion).filter(Objects::nonNull)
                .flatMap(s -> LINK.matcher(s).results().map(MatchResult::group))
                .toList();
    }

    // Opened means the model fetched the same page, ignoring scheme, www, query and trailing slash.
    private static boolean noneOpened(List<String> links, Collection<String> openedUrls) {
        Set<String> opened = openedUrls.stream().map(DefaultFactCheckService::page).collect(Collectors.toSet());
        return !links.isEmpty() && links.stream().map(DefaultFactCheckService::page).noneMatch(opened::contains);
    }

    private static String page(String url) {
        String rest = SCHEME_AND_WWW.matcher(url.strip()).replaceFirst("");
        int cut = rest.indexOf('?') >= 0 ? rest.indexOf('?') : rest.indexOf('#');
        rest = cut >= 0 ? rest.substring(0, cut) : rest;
        return (rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest).toLowerCase(Locale.ROOT);
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
