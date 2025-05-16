package com.elvischang.ailinebot.model;

import com.linecorp.bot.webhook.model.Event;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebhookRequest {
    private String destination;
    private List<Event> events;
}