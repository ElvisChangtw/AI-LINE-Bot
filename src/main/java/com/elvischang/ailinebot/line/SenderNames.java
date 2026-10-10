package com.elvischang.ailinebot.line;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.GroupUserProfileResponse;
import com.linecorp.bot.messaging.model.RoomUserProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class SenderNames {

    private static final Logger log = LoggerFactory.getLogger(SenderNames.class);
    private static final long TIMEOUT_SECONDS = 5;
    private static final int MAX_NAME_LENGTH = 10;
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
        try {
            String name = chatId.startsWith("R")
                    ? client.getRoomMemberProfile(chatId, userId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).body().displayName()
                    : client.getGroupMemberProfile(chatId, userId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).body().displayName();
            return Optional.ofNullable(name).map(SenderNames::clean).filter(n -> !n.isEmpty());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.info("sender name unavailable groupHash={} cause={}", Hashes.shortHash(chatId), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String clean(String name) {
        String plain = LINK.matcher(name).replaceAll("").replaceAll("\\s+", " ").strip();
        return plain.length() > MAX_NAME_LENGTH ? plain.substring(0, MAX_NAME_LENGTH) + "…" : plain;
    }
}
