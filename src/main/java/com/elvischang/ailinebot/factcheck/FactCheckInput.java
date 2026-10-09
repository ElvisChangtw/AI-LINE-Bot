package com.elvischang.ailinebot.factcheck;

import java.time.LocalDate;

public record FactCheckInput(String materialText, byte[] image, String imageMimeType, String userQuestion, LocalDate today) {
}
