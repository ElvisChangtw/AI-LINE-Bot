package com.elvischang.ailinebot.support;

import com.linecorp.bot.parser.LineSignatureValidator;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Webhooks {

    public static final String CHANNEL_SECRET = "test-secret";

    private static final LineSignatureValidator SIGNER =
            new LineSignatureValidator(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8));

    private Webhooks() {
    }

    public static String sign(String body) {
        return Base64.getEncoder().encodeToString(SIGNER.generateSignature(body.getBytes(StandardCharsets.UTF_8)));
    }
}
