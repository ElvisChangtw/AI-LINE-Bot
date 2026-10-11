package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.RecentMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class RecentMaterialPicker {

    // A link and its explanation usually arrive as separate bubbles seconds apart.
    static final Duration SAME_SENDER_GAP = Duration.ofMinutes(2);
    // Tells the model the material is its own earlier answer, so it explains or corrects itself.
    public static final String EARLIER_BOT_REPLY = "（這是 AskBot 之前在群組的回覆，家人在追問它）\n";
    static final String ORIGINAL_TOPIC = "原本在討論的內容：\n";
    static final String LATEST_BOT_REPLY = "AskBot 剛才的回覆：\n";

    private final MaterialRepository materials;
    private final BotProperties props;

    public RecentMaterialPicker(MaterialRepository materials, BotProperties props) {
        this.materials = materials;
        this.props = props;
    }

    public record Picked(Material material, String senderId, boolean ownReply) {
    }

    public Optional<Picked> pick(String groupId, String triggerMessageId, long triggerAt, boolean includeBotReplies) {
        long since = triggerAt - props.recentWindow().toMillis();
        return latestRun(materials.recentCandidates(groupId, since, triggerAt, triggerMessageId, includeBotReplies));
    }

    public static Material asFollowUp(Material botReply, String about) {
        return new Material(botReply.groupId(), botReply.messageId(), MaterialKind.TEXT, asFollowUp(about, botReply.text()),
                false, botReply.mediaStatus(), null, 0, botReply.createdAt(), botReply.expiresAt());
    }

    public static String asFollowUp(String about, String botReplyText) {
        if (about == null) {
            return EARLIER_BOT_REPLY + botReplyText;
        }
        return EARLIER_BOT_REPLY + ORIGINAL_TOPIC + about + "\n\n" + LATEST_BOT_REPLY + botReplyText;
    }

    // The message the model pointed at, merged with the same sender's bubbles just before it.
    public static Optional<Picked> at(List<RecentMessage> oldestFirst, RecentMessage target) {
        int index = oldestFirst.indexOf(target);
        if (index < 0 || target.material().botTrigger()) {
            return Optional.empty();
        }
        List<RecentMessage> upToTarget = oldestFirst.subList(0, index + 1).stream()
                .filter(m -> !m.material().botTrigger())
                .toList();
        return latestRun(upToTarget.reversed());
    }

    static Optional<Picked> latestRun(List<RecentMessage> newestFirst) {
        if (newestFirst.isEmpty()) {
            return Optional.empty();
        }
        RecentMessage latest = newestFirst.getFirst();
        if (latest.material().kind() == MaterialKind.BOT_REPLY) {
            return Optional.of(new Picked(latest.material(), null, true));
        }
        if (latest.material().kind() != MaterialKind.TEXT) {
            return Optional.of(new Picked(latest.material(), latest.senderId(), false));
        }
        List<Material> run = new ArrayList<>(List.of(latest.material()));
        for (RecentMessage older : newestFirst.subList(1, newestFirst.size())) {
            Material previous = run.getLast();
            boolean sameSender = latest.senderId() != null && Objects.equals(latest.senderId(), older.senderId());
            boolean close = previous.createdAt() - older.material().createdAt() <= SAME_SENDER_GAP.toMillis();
            if (!sameSender || !close || older.material().kind() != MaterialKind.TEXT) {
                break;
            }
            run.add(older.material());
        }
        List<String> texts = run.reversed().stream().map(Material::text).toList();
        Material newest = latest.material();
        Material merged = new Material(newest.groupId(), newest.messageId(), MaterialKind.TEXT, String.join("\n", texts),
                false, newest.mediaStatus(), null, 0, run.getLast().createdAt(), newest.expiresAt());
        return Optional.of(new Picked(merged, latest.senderId(), false));
    }
}
