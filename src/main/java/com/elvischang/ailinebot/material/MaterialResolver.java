package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MaterialResolver {

    private static final String IMAGINE_PREFIX = "/imagine ";

    private final MaterialRepository materials;
    private final BotProperties props;

    public MaterialResolver(MaterialRepository materials, BotProperties props) {
        this.materials = materials;
        this.props = props;
    }

    public Resolution resolve(Trigger trigger, long now) {
        String text = trigger.textWithoutMention();
        if (text.equals(IMAGINE_PREFIX.trim()) || text.startsWith(IMAGINE_PREFIX)) {
            String prompt = text.length() > IMAGINE_PREFIX.length() ? text.substring(IMAGINE_PREFIX.length()).trim() : "";
            return prompt.isEmpty() ? new Resolution.Notice(BotReplies.IMAGINE_NEEDS_PROMPT) : new Resolution.Imagine(prompt);
        }
        if (trigger.quotedMessageId() != null) {
            return materials.find(trigger.groupId(), trigger.quotedMessageId())
                    .map(this::checkable)
                    .orElse(new Resolution.Notice(BotReplies.QUOTE_NOT_FOUND));
        }
        // Attaching a recent message to an unrelated question would send a family member's words for no reason.
        if (!text.isBlank()) {
            return new Resolution.QuestionOnly();
        }
        long since = now - props.recentWindow().toMillis();
        List<Material> candidates = materials.recentCandidates(trigger.groupId(), since, trigger.messageId());
        if (candidates.isEmpty()) {
            return new Resolution.Notice(BotReplies.ASK_TO_QUOTE);
        }
        if (candidates.size() > 1) {
            return new Resolution.Notice(BotReplies.TOO_MANY_CANDIDATES);
        }
        return checkable(candidates.getFirst());
    }

    private Resolution checkable(Material material) {
        return switch (material.kind()) {
            case BOT_REPLY -> new Resolution.Notice(BotReplies.QUOTED_BOT_REPLY);
            case AUDIO, VIDEO -> new Resolution.Notice(BotReplies.MEDIA_UNSUPPORTED);
            case IMAGE -> switch (material.mediaStatus()) {
                case FAILED -> new Resolution.Notice(BotReplies.IMAGE_GONE);
                case TOO_LARGE -> new Resolution.Notice(BotReplies.IMAGE_TOO_LARGE);
                default -> new Resolution.FactCheck(material);
            };
            case TEXT -> new Resolution.FactCheck(material);
        };
    }
}
