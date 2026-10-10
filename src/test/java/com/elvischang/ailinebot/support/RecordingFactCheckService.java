package com.elvischang.ailinebot.support;

import com.elvischang.ailinebot.factcheck.FactCheckReply;
import com.elvischang.ailinebot.factcheck.FactCheckService;
import com.elvischang.ailinebot.store.Material;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class RecordingFactCheckService implements FactCheckService {

    public static final String QUESTION_ONLY = "question";

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Material, String> behavior = RecordingFactCheckService::defaultResult;
    private volatile FactCheckService delegate;

    private static String defaultResult(Material material) {
        return "TEST RESULT " + (material == null ? QUESTION_ONLY : material.messageId());
    }

    @Override
    public FactCheckReply verify(String groupId, Material material, String userQuestion) {
        calls.add(material == null ? QUESTION_ONLY : material.messageId());
        FactCheckService real = delegate;
        return real != null ? real.verify(groupId, material, userQuestion) : FactCheckReply.notice(behavior.apply(material));
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public void behave(Function<Material, String> newBehavior) {
        this.behavior = newBehavior;
    }

    public void delegateTo(FactCheckService real) {
        this.delegate = real;
    }

    public void reset() {
        calls.clear();
        behavior = RecordingFactCheckService::defaultResult;
        delegate = null;
    }
}
