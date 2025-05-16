package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.service.LineMessageService;
import com.linecorp.bot.webhook.model.*;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/")
public class LineBotController {

    private final LineMessageService lineMessageService;

    public LineBotController(LineMessageService lineMessageService) {
        this.lineMessageService = lineMessageService;
    }

    @GetMapping("/hello")
    public void hello() {
        System.out.println("Hello World");
    }

    @PostMapping("/callback")
    public void callback(@RequestBody CallbackRequest request) {
        for (Event event : request.events()) {
            if (event instanceof MessageEvent messageEvent){
                if( messageEvent.message() instanceof TextMessageContent) {
                    lineMessageService.handleTextMessage(messageEvent);
                } else if (messageEvent.message() instanceof AudioMessageContent) {
                    lineMessageService.handleAudioMessage(messageEvent);
                }
            }
        }
    }
}