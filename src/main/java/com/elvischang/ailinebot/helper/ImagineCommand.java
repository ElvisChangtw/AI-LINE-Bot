package com.elvischang.ailinebot.helper;

import com.elvischang.ailinebot.service.ImageGenerationService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.webhook.model.MessageEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Component
@Order(0)
public class ImagineCommand implements LineCommandStrategy {

    private final ImageGenerationService imageGenerationService;
    private final MessagingApiClient messagingApiClient;

    public ImagineCommand(ImageGenerationService imageGenerationService, MessagingApiClient messagingApiClient) {
        this.imageGenerationService = imageGenerationService;
        this.messagingApiClient = messagingApiClient;
    }


    @Override
    public boolean supports(String userText) {
        return userText.startsWith("/imagine ");
    }

    @Override
    public void handle(MessageEvent event, String text) {
        String prompt = text.substring(9);
        try {
            String url = prompt.contains("高畫質")
                    ? imageGenerationService.generateImageWithGptImage1(prompt)
                    : imageGenerationService.generateImage(prompt);

            ImageMessage img = new ImageMessage(URI.create(url), URI.create(url));
            ReplyMessageRequest req = new ReplyMessageRequest.Builder(event.replyToken(), List.of(img)).build();
            messagingApiClient.replyMessage(req).get();

        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("生成圖片失敗", e);
        }
    }
}