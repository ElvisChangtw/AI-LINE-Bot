package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.service.LineMessageService;
import com.elvischang.ailinebot.webhook.LineSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.webhook.model.AudioMessageContent;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class LineBotController {

    private static final Logger log = LoggerFactory.getLogger(LineBotController.class);

    private final LineSignatureVerifier signatureVerifier;
    private final LineMessageService lineMessageService;
    private final ObjectMapper lineObjectMapper = ModelObjectMapper.createNewObjectMapper();

    public LineBotController(LineSignatureVerifier signatureVerifier, LineMessageService lineMessageService) {
        this.signatureVerifier = signatureVerifier;
        this.lineMessageService = lineMessageService;
    }

    @GetMapping("/hello")
    public void hello() {
        System.out.println("Hello World");
    }

    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@RequestHeader(name = "x-line-signature", required = false) String signature,
                                         @RequestBody(required = false) byte[] body) {
        if (!signatureVerifier.isValid(body, signature)) {
            log.warn("webhook rejected: invalid signature");
            return ResponseEntity.badRequest().build();
        }
        CallbackRequest request;
        try {
            request = lineObjectMapper.readValue(body, CallbackRequest.class);
        } catch (IOException e) {
            log.warn("webhook rejected: body is not a LINE callback");
            return ResponseEntity.badRequest().build();
        }
        for (Event event : request.events()) {
            if (event instanceof MessageEvent messageEvent) {
                if (messageEvent.message() instanceof TextMessageContent) {
                    lineMessageService.handleTextMessage(messageEvent);
                } else if (messageEvent.message() instanceof AudioMessageContent) {
                    lineMessageService.handleAudioMessage(messageEvent);
                }
            }
        }
        return ResponseEntity.ok().build();
    }
}
