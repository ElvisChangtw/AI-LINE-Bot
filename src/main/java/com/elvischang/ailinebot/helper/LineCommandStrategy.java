package com.elvischang.ailinebot.helper;

import com.linecorp.bot.webhook.model.MessageEvent;

public interface LineCommandStrategy {
    boolean supports(String userText);
    void handle(MessageEvent event, String text);
}