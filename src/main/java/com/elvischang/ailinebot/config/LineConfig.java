package com.elvischang.ailinebot.config;


import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class LineConfig {
    @Value("${line.bot.channel-token}")
    private String channelToken;

    @Bean
    public MessagingApiClient lineMessagingClient() {
        return MessagingApiClient.builder(channelToken).build();
    }

    @Bean
    public MessagingApiBlobClient messagingApiBlobClient(
            @Value("${line.bot.channel-token}") String token) {
        return MessagingApiBlobClient.builder(() -> token)
                .build();
    }
}