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

    public static Material asFollowUp(Material botReply) {
        return new Material(botReply.groupId(), botReply.messageId(), MaterialKind.TEXT, EARLIER_BOT_REPLY + botReply.text(),
                false, botReply.mediaStatus(), null, 0, botReply.createdAt(), botReply.expiresAt());
    }

    static Optional<Picked> latestRun(List<RecentMessage> newestFirst) {
        if (newestFirst.isEmpty()) {
            return Optional.empty();
        }
        RecentMessage latest = newestFirst.getFirst();
        if (latest.material().kind() == MaterialKind.BOT_REPLY) {
            return Optional.of(new Picked(asFollowUp(latest.material()), null, true));
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
