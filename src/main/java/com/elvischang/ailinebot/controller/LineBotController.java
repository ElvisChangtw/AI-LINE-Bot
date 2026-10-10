package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.webhook.LineSignatureVerifier;
import com.elvischang.ailinebot.webhook.WebhookIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.webhook.model.CallbackRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class LineBotController {

    private static final Logger log = LoggerFactory.getLogger(LineBotController.class);

    private final LineSignatureVerifier signatureVerifier;
    private final WebhookIngestService ingestService;
    private final ObjectMapper lineObjectMapper = ModelObjectMapper.createNewObjectMapper();

    public LineBotController(LineSignatureVerifier signatureVerifier, WebhookIngestService ingestService) {
        this.signatureVerifier = signatureVerifier;
        this.ingestService = ingestService;
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
        if (request == null || request.events() == null) {
            log.warn("webhook rejected: body is not a LINE callback");
            return ResponseEntity.badRequest().build();
        }
        ingestService.ingest(request.events());
        return ResponseEntity.ok().build();
    }
}
