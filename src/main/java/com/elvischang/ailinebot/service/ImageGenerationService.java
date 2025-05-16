package com.elvischang.ailinebot.service;

import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.stereotype.Service;

@Service
public class ImageGenerationService {

    private final OpenAiImageModel imageModel;

    public ImageGenerationService(OpenAiImageModel imageModel) {
        this.imageModel = imageModel;
    }

    public String generateImage(String prompt) {
        ImagePrompt imagePrompt = new ImagePrompt(prompt);
        ImageResponse response = imageModel.call(imagePrompt);
        return response.getResult().getOutput().getUrl();
    }

    public String generateImageWithGptImage1(String prompt) {
        ImagePrompt ip = new ImagePrompt(
                prompt,
                OpenAiImageOptions.builder()
                        .withModel("gpt-image-1")
                        .withQuality("medium")
                        .build()
        );

        return imageModel.call(ip)
                .getResults()
                .getFirst()
                .getOutput()
                .getUrl();
    }
}