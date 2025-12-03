package com.elvischang.ailinebot.helper;

import com.elvischang.ailinebot.service.OllamaService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.linecorp.bot.webhook.model.MessageEvent;

import java.util.List;
import java.util.concurrent.ExecutionException;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultTextCommand implements LineCommandStrategy {

    private final OllamaService ollamaService;
    private final MessagingApiClient messagingApiClient;

    public DefaultTextCommand(OllamaService ollamaService, MessagingApiClient messagingApiClient) {
        this.ollamaService = ollamaService;
        this.messagingApiClient = messagingApiClient;
    }

    @Override
    public boolean supports(String userText) {
        return true;
    }

    @Override
    public void handle(MessageEvent event, String userText) {
        // Log replyToken 與使用者輸入，方便除錯 Invalid reply token 問題
        System.out.println("[DefaultTextCommand] replyToken = " + event.replyToken());
        System.out.println("[DefaultTextCommand] userText   = " + userText);

        String reply = StringUtils.defaultIfBlank(ollamaService.generateResponse(userText),
                "抱歉，我不太理解你的問題，可以換個說法嗎？");

        TextMessage text = new TextMessage(reply);
        ReplyMessageRequest req = new ReplyMessageRequest.Builder(event.replyToken(), List.of(text)).build();

        try {
            messagingApiClient.replyMessage(req).get();
            System.out.println("[DefaultTextCommand] replyMessage sent successfully.");
        } catch (InterruptedException | ExecutionException e) {
            System.out.println("[DefaultTextCommand] replyMessage failed. replyToken = " + event.replyToken());
            Thread.currentThread().interrupt();
            throw new RuntimeException("文字回覆失敗", e);
        }
    }
}