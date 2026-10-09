package com.elvischang.ailinebot.material;

public record Trigger(String groupId, String messageId, String textWithoutMention, String quotedMessageId) {
}
