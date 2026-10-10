package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
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
                    .map(m -> isFollowUp(m, text) ? new Resolution.FactCheck(m) : checkable(m))
                    .orElse(new Resolution.Notice(BotReplies.QUOTE_NOT_FOUND));
        }
        // The job looks back only if the model says the question is about a message it cannot see.
        if (!text.isBlank()) {
            return new Resolution.QuestionOnly();
        }
        // A bare mention means "that message above"; the job picks which one once it runs.
        long since = now - props.recentWindow().toMillis();
        if (materials.recentCandidates(trigger.groupId(), since, now, trigger.messageId(), false).isEmpty()) {
            return new Resolution.Notice(BotReplies.ASK_TO_QUOTE);
        }
        return new Resolution.QuestionOnly();
    }

    // Asking something about our own earlier answer is a follow-up; just quoting it is not.
    private static boolean isFollowUp(Material material, String text) {
        return material.kind() == MaterialKind.BOT_REPLY && material.text() != null && !Questions.normalize(text).isEmpty();
    }

    private Resolution checkable(Material material) {
        return switch (material.kind()) {
            case BOT_REPLY -> new Resolution.Notice(BotReplies.QUOTED_BOT_REPLY);
            case AUDIO, VIDEO -> new Resolution.FactCheck(material);
            case IMAGE -> switch (material.mediaStatus()) {
                case FAILED -> new Resolution.Notice(BotReplies.IMAGE_GONE);
                case TOO_LARGE -> new Resolution.Notice(BotReplies.IMAGE_TOO_LARGE);
                default -> new Resolution.FactCheck(material);
            };
            case TEXT -> new Resolution.FactCheck(material);
        };
    }
}
