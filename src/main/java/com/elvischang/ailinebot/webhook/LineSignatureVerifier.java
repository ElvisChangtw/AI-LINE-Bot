package com.elvischang.ailinebot.webhook;

import com.linecorp.bot.parser.LineSignatureValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class LineSignatureVerifier {

    private final LineSignatureValidator validator;

    public LineSignatureVerifier(@Value("${line.bot.channel-secret}") String channelSecret) {
        if (channelSecret == null || channelSecret.isBlank()) {
            throw new IllegalStateException("line.bot.channel-secret is blank");
        }
        this.validator = new LineSignatureValidator(channelSecret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isValid(byte[] body, String signature) {
        if (body == null || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            return validator.validateSignature(body, signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
