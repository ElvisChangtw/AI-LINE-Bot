package com.elvischang.ailinebot.support;

import com.elvischang.ailinebot.factcheck.FactCheckService;
import com.elvischang.ailinebot.store.Material;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class RecordingFactCheckService implements FactCheckService {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Material, String> behavior = RecordingFactCheckService::defaultResult;

    private static String defaultResult(Material material) {
        return "TEST RESULT " + material.messageId();
    }

    @Override
    public String verify(Material material, String userQuestion) {
        calls.add(material.messageId());
        return behavior.apply(material);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public void behave(Function<Material, String> newBehavior) {
        this.behavior = newBehavior;
    }

    public void reset() {
        calls.clear();
        behavior = RecordingFactCheckService::defaultResult;
    }
}
