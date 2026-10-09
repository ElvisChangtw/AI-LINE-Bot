package com.elvischang.ailinebot.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.parser.LineSignatureValidator;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.Event;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public final class Webhooks {

    public static final String CHANNEL_SECRET = "test-secret";

    private static final LineSignatureValidator SIGNER =
            new LineSignatureValidator(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper LINE_MAPPER = ModelObjectMapper.createNewObjectMapper();

    private Webhooks() {
    }

    public static String sign(String body) {
        return Base64.getEncoder().encodeToString(SIGNER.generateSignature(body.getBytes(StandardCharsets.UTF_8)));
    }

    public static String body(String... events) {
        return "{\"destination\":\"Ubot\",\"events\":[" + String.join(",", events) + "]}";
    }

    public static List<Event> parse(String body) {
        try {
            return LINE_MAPPER.readValue(body, CallbackRequest.class).events();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String groupText(String eventId, String groupId, String messageId, String text) {
        return messageEvent(eventId, group(groupId),
                "{\"type\":\"text\",\"id\":\"%s\",\"quoteToken\":\"qt-%s\",\"text\":\"%s\"}"
                        .formatted(messageId, messageId, text));
    }

    public static String groupMention(String eventId, String groupId, String messageId,
                                      String textAfterMention, String quotedMessageId) {
        String quoted = quotedMessageId == null ? "" : ",\"quotedMessageId\":\"" + quotedMessageId + "\"";
        return messageEvent(eventId, group(groupId),
                ("{\"type\":\"text\",\"id\":\"%s\",\"quoteToken\":\"qt-%s\",\"text\":\"@bot %s\"%s,"
                        + "\"mention\":{\"mentionees\":[{\"index\":0,\"length\":4,\"type\":\"user\",\"userId\":\"Ubot\",\"isSelf\":true}]}}")
                        .formatted(messageId, messageId, textAfterMention, quoted));
    }

    public static String groupImage(String eventId, String groupId, String messageId) {
        return messageEvent(eventId, group(groupId),
                "{\"type\":\"image\",\"id\":\"%s\",\"quoteToken\":\"qt-%s\",\"contentProvider\":{\"type\":\"line\"}}"
                        .formatted(messageId, messageId));
    }

    public static String groupAudio(String eventId, String groupId, String messageId) {
        return messageEvent(eventId, group(groupId),
                "{\"type\":\"audio\",\"id\":\"%s\",\"duration\":1000,\"contentProvider\":{\"type\":\"line\"}}"
                        .formatted(messageId));
    }

    public static String userText(String eventId, String userId, String messageId, String text) {
        return messageEvent(eventId, "{\"type\":\"user\",\"userId\":\"" + userId + "\"}",
                "{\"type\":\"text\",\"id\":\"%s\",\"quoteToken\":\"qt-%s\",\"text\":\"%s\"}"
                        .formatted(messageId, messageId, text));
    }

    public static String join(String eventId, String groupId) {
        return ("{\"type\":\"join\",\"mode\":\"active\",\"timestamp\":1760054400000,\"webhookEventId\":\"%s\","
                + "\"deliveryContext\":{\"isRedelivery\":false},\"replyToken\":\"rt-%s\",\"source\":%s}")
                .formatted(eventId, eventId, group(groupId));
    }

    public static String unsend(String eventId, String groupId, String messageId) {
        return ("{\"type\":\"unsend\",\"mode\":\"active\",\"timestamp\":1760054400000,\"webhookEventId\":\"%s\","
                + "\"deliveryContext\":{\"isRedelivery\":false},\"source\":%s,\"unsend\":{\"messageId\":\"%s\"}}")
                .formatted(eventId, group(groupId), messageId);
    }

    public static String unknownEvent(String eventId) {
        return ("{\"type\":\"somethingNew\",\"mode\":\"active\",\"timestamp\":1760054400000,\"webhookEventId\":\"%s\","
                + "\"deliveryContext\":{\"isRedelivery\":false}}").formatted(eventId);
    }

    private static String group(String groupId) {
        return "{\"type\":\"group\",\"groupId\":\"" + groupId + "\",\"userId\":\"Uuser\"}";
    }

    private static String messageEvent(String eventId, String source, String message) {
        return ("{\"type\":\"message\",\"mode\":\"active\",\"timestamp\":1760054400000,\"webhookEventId\":\"%s\","
                + "\"deliveryContext\":{\"isRedelivery\":false},\"replyToken\":\"rt-%s\",\"source\":%s,\"message\":%s}")
                .formatted(eventId, eventId, source, message);
    }
}
