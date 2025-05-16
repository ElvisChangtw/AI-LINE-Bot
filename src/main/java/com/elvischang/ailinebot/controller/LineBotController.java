package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.service.LineMessageService;
import com.linecorp.bot.webhook.model.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/")
public class LineBotController {

    private final LineMessageService lineMessageService;

    public LineBotController(LineMessageService lineMessageService) {
        this.lineMessageService = lineMessageService;
    }

    @PostMapping("/callback")
    public void callback(@RequestBody CallbackRequest request) {
        for (Event event : request.events()) {
            if (event instanceof MessageEvent messageEvent){
                if( messageEvent.message() instanceof TextMessageContent) {
                    lineMessageService.handleTextMessage(messageEvent);
                }
            }
        }
    }
}