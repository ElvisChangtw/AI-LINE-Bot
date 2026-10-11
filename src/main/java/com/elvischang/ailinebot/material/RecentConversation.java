package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.line.SenderNames;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.RecentMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class RecentConversation {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Taipei"));
    private static final int MAX_LINE_CHARS = 300;
    private static final Duration NAME_BUDGET = Duration.ofSeconds(3);
    private static final String BOT_NAME = "AskBot";
    private static final String UNKNOWN_NAME = "家人";

    private final MaterialRepository materials;
    private final SenderNames senderNames;
    private final BotProperties props;

    public RecentConversation(MaterialRepository materials, SenderNames senderNames, BotProperties props) {
        this.materials = materials;
        this.senderNames = senderNames;
        this.props = props;
    }

    // Line n of text is messages.get(n - 1), so the model can point at a message by its number.
    public record Snapshot(String text, List<RecentMessage> messages) {

        public static final Snapshot EMPTY = new Snapshot(null, List.of());

        public Optional<RecentMessage> message(Integer number) {
            return number == null || number < 1 || number > messages.size() ? Optional.empty() : Optional.of(messages.get(number - 1));
        }
    }

    public Snapshot of(String groupId, String triggerMessageId, long triggerAt) {
        long since = triggerAt - props.recentWindow().toMillis();
        List<RecentMessage> messages = materials.conversation(groupId, since, triggerAt, triggerMessageId, props.conversationMessages());
        if (messages.isEmpty()) {
            return Snapshot.EMPTY;
        }
        List<String> senders = messages.stream().map(RecentMessage::senderId).toList();
        Map<String, String> names = senderNames.displayNames(groupId, senders, NAME_BUDGET);
        String text = IntStream.range(0, messages.size())
                .mapToObj(i -> line(i + 1, messages.get(i), name(messages.get(i), names)))
                .collect(Collectors.joining("\n"));
        return new Snapshot(text, messages);
    }

    private static String name(RecentMessage message, Map<String, String> names) {
        if (message.material().kind() == MaterialKind.BOT_REPLY) {
            return BOT_NAME;
        }
        String name = message.senderId() == null ? null : names.get(message.senderId());
        // A family member named "AskBot" must not pass for the bot's own earlier replies.
        return name == null || name.equalsIgnoreCase(BOT_NAME) ? UNKNOWN_NAME : name;
    }

    private static String line(int number, RecentMessage message, String name) {
        Material m = message.material();
        String who = m.botTrigger() ? name + "（問 AskBot）" : name;
        return "#" + number + " " + TIME.format(Instant.ofEpochMilli(m.createdAt())) + " " + who + "：" + content(m);
    }

    private static String content(Material m) {
        return switch (m.kind()) {
            case TEXT -> m.text() == null || m.text().isBlank() ? "（只 @ 了 AskBot）" : oneLine(m.text());
            case BOT_REPLY -> oneLine(m.text());
            case IMAGE -> "（傳了一張圖片）";
            case AUDIO -> transcribed(m) ? "（語音訊息，轉成文字：" + oneLine(m.text()) + "）" : "（傳了一段語音訊息）";
            case VIDEO -> transcribed(m) ? "（影片，語音轉成文字：" + oneLine(m.text()) + "）" : "（傳了一段影片）";
        };
    }

    private static boolean transcribed(Material m) {
        return m.mediaStatus() == MediaStatus.STORED && m.text() != null && !m.text().isBlank();
    }

    private static String oneLine(String text) {
        String flat = SenderNames.BLANKS.matcher(text).replaceAll(" ").strip();
        return flat.codePointCount(0, flat.length()) <= MAX_LINE_CHARS ? flat
                : flat.substring(0, flat.offsetByCodePoints(0, MAX_LINE_CHARS)) + "…";
    }
}
