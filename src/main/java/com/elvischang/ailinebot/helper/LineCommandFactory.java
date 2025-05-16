package com.elvischang.ailinebot.helper;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LineCommandFactory {

    private final List<LineCommandStrategy> strategies;

    public LineCommandFactory(List<LineCommandStrategy> strategies) {
        this.strategies = strategies;
    }


    public LineCommandStrategy resolve(String userText) {
        return strategies.stream()
                .filter(s -> s.supports(userText))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No strategy found"));
    }
}