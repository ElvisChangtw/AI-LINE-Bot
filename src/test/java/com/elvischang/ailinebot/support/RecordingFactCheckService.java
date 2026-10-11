package com.elvischang.ailinebot.support;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.factcheck.ReplyMode;
import com.elvischang.ailinebot.factcheck.Usage;

import com.elvischang.ailinebot.factcheck.FactCheckReply;
import com.elvischang.ailinebot.factcheck.FactCheckService;
import com.elvischang.ailinebot.store.Material;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class RecordingFactCheckService implements FactCheckService {

    public static final String QUESTION_ONLY = "question";

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> materialTexts = new CopyOnWriteArrayList<>();
    private final List<String> conversations = new CopyOnWriteArrayList<>();
    private volatile boolean needsMaterialWhenAlone;
    private volatile Integer targetMessage;
    private volatile Function<Material, String> behavior = RecordingFactCheckService::defaultResult;
    private volatile FactCheckService delegate;

    private static String defaultResult(Material material) {
        return "TEST RESULT " + (material == null ? QUESTION_ONLY : material.messageId());
    }

    @Override
    public FactCheckReply verify(String groupId, Material material, String userQuestion, String conversation) {
        calls.add(material == null ? QUESTION_ONLY : material.messageId());
        conversations.add(String.valueOf(conversation));
        if (material != null) {
            materialTexts.add(String.valueOf(material.text()));
        }
        if (material == null && needsMaterialWhenAlone) {
            return new FactCheckReply(BotReplies.ASK_TO_QUOTE, null, ReplyMode.NEED_MATERIAL, Usage.NONE, 0, targetMessage);
        }
        FactCheckService real = delegate;
        return real != null ? real.verify(groupId, material, userQuestion, conversation) : FactCheckReply.notice(behavior.apply(material));
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public List<String> materialTexts() {
        return List.copyOf(materialTexts);
    }

    public List<String> conversations() {
        return List.copyOf(conversations);
    }

    public void askForMaterialWhenAlone() {
        this.needsMaterialWhenAlone = true;
    }

    public void askForMaterialAt(int number) {
        this.targetMessage = number;
        this.needsMaterialWhenAlone = true;
    }

    public void behave(Function<Material, String> newBehavior) {
        this.behavior = newBehavior;
    }

    public void delegateTo(FactCheckService real) {
        this.delegate = real;
    }

    public void reset() {
        calls.clear();
        materialTexts.clear();
        conversations.clear();
        needsMaterialWhenAlone = false;
        targetMessage = null;
        behavior = RecordingFactCheckService::defaultResult;
        delegate = null;
    }
}
