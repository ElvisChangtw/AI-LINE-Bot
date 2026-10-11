package com.elvischang.ailinebot.factcheck;

import java.time.LocalDate;

// conversation is the numbered recent group chat, or null when there is none.
public record FactCheckInput(String materialText, byte[] image, String imageMimeType, String userQuestion, LocalDate today,
                             String conversation) {

    public FactCheckInput(String materialText, byte[] image, String imageMimeType, String userQuestion, LocalDate today) {
        this(materialText, image, imageMimeType, userQuestion, today, null);
    }
}
