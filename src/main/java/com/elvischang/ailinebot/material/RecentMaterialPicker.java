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

    private final MaterialRepository materials;
    private final BotProperties props;

    public RecentMaterialPicker(MaterialRepository materials, BotProperties props) {
        this.materials = materials;
        this.props = props;
    }

    public Optional<Material> pick(String groupId, String triggerMessageId, long triggerAt) {
        long since = triggerAt - props.recentWindow().toMillis();
        return latestRun(materials.recentCandidates(groupId, since, triggerAt, triggerMessageId));
    }

    static Optional<Material> latestRun(List<RecentMessage> newestFirst) {
        if (newestFirst.isEmpty()) {
            return Optional.empty();
        }
        RecentMessage latest = newestFirst.getFirst();
        if (latest.material().kind() != MaterialKind.TEXT) {
            return Optional.of(latest.material());
        }
        List<Material> run = new ArrayList<>(List.of(latest.material()));
        for (RecentMessage older : newestFirst.subList(1, newestFirst.size())) {
            Material previous = run.getLast();
            boolean sameSender = latest.senderHash() != null && Objects.equals(latest.senderHash(), older.senderHash());
            boolean close = previous.createdAt() - older.material().createdAt() <= SAME_SENDER_GAP.toMillis();
            if (!sameSender || !close || older.material().kind() != MaterialKind.TEXT) {
                break;
            }
            run.add(older.material());
        }
        List<String> texts = run.reversed().stream().map(Material::text).toList();
        Material newest = latest.material();
        return Optional.of(new Material(newest.groupId(), newest.messageId(), MaterialKind.TEXT, String.join("\n", texts),
                false, newest.mediaStatus(), null, 0, run.getLast().createdAt(), newest.expiresAt()));
    }
}
