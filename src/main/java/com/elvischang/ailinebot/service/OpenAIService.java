package com.elvischang.ailinebot.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class OpenAIService {

    private final ChatClient.Builder chatClientBuilder;

    public OpenAIService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public String generateResponse(String userMessage) {

        ChatClient chatClient = chatClientBuilder.build();

        return chatClient.prompt()
                .user(userMessage.trim())
                .call()
                .content();
    }
}
