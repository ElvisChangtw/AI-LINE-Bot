package com.elvischang.ailinebot.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OpenAiHttpConfig {

    @Bean
    RestClientCustomizer longTimeoutCustomizer() {

        Duration timeout = Duration.ofSeconds(300);
        return builder -> builder.requestFactory(
                ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(100))
                                .withReadTimeout(timeout)
                )
        );
    }
}