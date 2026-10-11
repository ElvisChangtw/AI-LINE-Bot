package com.elvischang.ailinebot.line;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class SenderNames {

    private static final Logger log = LoggerFactory.getLogger(SenderNames.class);
    private static final long TIMEOUT_SECONDS = 5;
    private static final int MAX_NAME_LENGTH = 10;
    // Unicode line and paragraph separators are not \s but still break lines in a prompt.
    public static final Pattern BLANKS = Pattern.compile("[\\s\\p{Zs}\\p{Zl}\\p{Zp}\\p{Cc}]+");
    private static final Pattern LINK = Pattern.compile("(https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);

    private final MessagingApiClient client;

    public SenderNames(MessagingApiClient client) {
        this.client = client;
    }

    // The name only labels our own reply; any failure just leaves it out.
    public Optional<String> displayName(String chatId, String userId) {
        if (chatId == null || userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(displayNames(chatId, List.of(userId), Duration.ofSeconds(TIMEOUT_SECONDS)).get(userId));
    }

    // Looks the names up in parallel; whoever has not answered within the budget is simply left out.
    public Map<String, String> displayNames(String chatId, Collection<String> userIds, Duration budget) {
        Map<String, CompletableFuture<String>> pending = new LinkedHashMap<>();
        userIds.stream().filter(Objects::nonNull).distinct().forEach(id -> pending.put(id, profileName(chatId, id)));
        long deadline = System.nanoTime() + budget.toNanos();
        Map<String, String> names = new HashMap<>();
        for (Map.Entry<String, CompletableFuture<String>> entry : pending.entrySet()) {
            try {
                String name = entry.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                Optional.ofNullable(name).map(SenderNames::clean).filter(n -> !n.isEmpty())
                        .ifPresent(n -> names.put(entry.getKey(), n));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                entry.getValue().cancel(true);
                log.info("sender name unavailable groupHash={} cause={}", Hashes.shortHash(chatId), e.getClass().getSimpleName());
            }
        }
        return names;
    }

    private CompletableFuture<String> profileName(String chatId, String userId) {
        try {
            return chatId.startsWith("R")
                    ? client.getRoomMemberProfile(chatId, userId).thenApply(r -> r.body().displayName())
                    : client.getGroupMemberProfile(chatId, userId).thenApply(r -> r.body().displayName());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String clean(String name) {
        String plain = BLANKS.matcher(LINK.matcher(name).replaceAll("")).replaceAll(" ").strip();
        return plain.length() > MAX_NAME_LENGTH ? plain.substring(0, MAX_NAME_LENGTH) + "…" : plain;
    }
}
