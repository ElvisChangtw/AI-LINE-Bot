package com.elvischang.ailinebot.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OpenAIService {

    private final ChatClient chatClient;

    public OpenAIService(ChatClient.Builder builder,
                         @Value("${openai.system-prompt}") String systemPrompt) {

        this.chatClient = builder
                .defaultSystem(systemPrompt)
                .build();
    }

    public String generateResponse(String userMessage) {
        return chatClient.prompt()
                .user(userMessage.trim())
                .call()
                .content();
    }
}