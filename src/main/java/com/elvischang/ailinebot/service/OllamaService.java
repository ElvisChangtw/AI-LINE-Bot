package com.elvischang.ailinebot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaService {

    private final RestClient restClient;
    private final String systemPrompt;
    private final String model;

    public OllamaService(
            @Value("${ollama.system-prompt:}") String systemPrompt,
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.model:qwen2.5:7b}") String model
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.systemPrompt = systemPrompt;
        this.model = model;
    }

    /**
     * 呼叫本機 Ollama /api/generate，並回傳 JSON 中的 response 欄位內容。
     */
    public String generateResponse(String userMessage) {
        String fullPrompt;
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            fullPrompt = userMessage.trim();
        } else {
            fullPrompt = systemPrompt + "\n\n使用者輸入：\n" + userMessage.trim();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("prompt", fullPrompt);

        OllamaResponse res = restClient.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OllamaResponse.class);

        return res != null ? res.getResponse() : null;
    }

    /**
     * 對應 Ollama /api/generate 回傳的 JSON (只保留需要的欄位)
     */
    public static class OllamaResponse {

        private String model;
        private String response;
        private boolean done;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getResponse() {
            return response;
        }

        public void setResponse(String response) {
            this.response = response;
        }

        public boolean isDone() {
            return done;
        }

        public void setDone(boolean done) {
            this.done = done;
        }
    }
}


