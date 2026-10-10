package com.elvischang.ailinebot.config;

import com.elvischang.ailinebot.delivery.LineReplyClient;
import com.elvischang.ailinebot.factcheck.FactCheckProperties;
import com.elvischang.ailinebot.webhook.LineSignatureVerifier;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredSecretsTest {

    @Test
    void blankLineSecretsStopTheAppFromStarting() {
        assertThatThrownBy(() -> new LineSignatureVerifier(" ")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line.bot.channel-secret");
        assertThatThrownBy(() -> new LineReplyClient(URI.create("https://api.line.me/"), "", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("line.bot.channel-token");
    }

    @Test
    void aBlankOpenAiKeyStopsTheAppFromStarting() {
        assertThatThrownBy(() -> new FactCheckProperties("https://api.openai.com/v1", " ", "gpt-6-luna", "medium", 3,
                Duration.ofSeconds(40), 1, 1, 1, 1, 1, Duration.ofHours(24), "v2"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("factcheck.api-key");
    }
}
