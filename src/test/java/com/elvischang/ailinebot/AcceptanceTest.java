package com.elvischang.ailinebot;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupAudio;
import static com.elvischang.ailinebot.support.Webhooks.groupImage;
import static com.elvischang.ailinebot.support.Webhooks.groupMention;
import static com.elvischang.ailinebot.support.Webhooks.groupText;
import static com.elvischang.ailinebot.support.Webhooks.userText;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AcceptanceTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";

    private SendState sendStateOf(String triggerMessageId) {
        return jdbc.sql("SELECT send_state FROM job WHERE trigger_message_id = :m").param("m", triggerMessageId)
                .query(String.class).optional().map(SendState::valueOf).orElse(null);
    }

    private String materialKind(String groupId, String messageId) {
        return jdbc.sql("SELECT kind FROM material WHERE group_id = :g AND message_id = :m")
                .param("g", groupId).param("m", messageId).query(String.class).optional().orElse(null);
    }

    private String mediaStatus(String groupId, String messageId) {
        return jdbc.sql("SELECT media_status FROM material WHERE group_id = :g AND message_id = :m")
                .param("g", groupId).param("m", messageId).query(String.class).optional().orElse(null);
    }

    @Test
    void plainGroupMessagesAndOneOnOneChatsNeverCallTheModelOrLine() throws Exception {
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/103/content")).willReturn(aResponse().withStatus(200).withBody("img")));
        postWebhook(body(groupText("e1", "Cg1", "100", "轉傳的訊息"), groupAudio("e2", "Cg1", "101"),
                userText("e3", "U1", "102", "私訊"), groupImage("e4", "Cg1", "103")));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM job").query(Integer.class).single()).isZero();
        assertThat(materialKind("Cg1", "100")).isEqualTo("TEXT");
        assertThat(materialKind("Cg1", "101")).isEqualTo("AUDIO");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM material WHERE message_id = '102'").query(Integer.class).single()).isZero();
        await().atMost(Duration.ofSeconds(5)).until(() -> "STORED".equals(mediaStatus("Cg1", "103")));
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(factChecks.calls()).isEmpty();
            LINE.verify(0, postRequestedFor(urlEqualTo(REPLY)));
        });
    }

    @Test
    void quotingAMessageFromAnotherGroupIsNotFound() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "99", "A 群的訊息")));
        postWebhook(body(groupMention("e2", "Cg2", "100", "這是真的嗎", "99")));

        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);
        assertThat(factChecks.calls()).isEmpty();
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.QUOTE_NOT_FOUND))));
    }

    @Test
    void redeliveredWebhookIsAnsweredOnce() throws Exception {
        stubReplyOk("900");
        String webhook = body(groupText("e1", "Cg1", "99", "轉傳的訊息"), groupMention("e2", "Cg1", "100", "這是真的嗎", "99"));
        postWebhook(webhook);
        postWebhook(webhook);

        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(factChecks.calls()).containsExactly("99");
            LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
        });
    }

    @Test
    void twoMessagesArrivingTogetherDeliverAWaitingResultOnce() throws Exception {
        stubReplyOk("900");
        factChecks.behave(m -> {
            clock.advance(Duration.ofSeconds(60));
            return "SLOW";
        });
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));
        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.WAITING_NEXT);

        var pool = Executors.newFixedThreadPool(2);
        List<Callable<Void>> posts = List.of(
                () -> { postWebhook(body(groupText("e3", "Cg1", "101", "早安"))); return null; },
                () -> { postWebhook(body(groupText("e4", "Cg1", "102", "午安"))); return null; });
        try {
            for (var future : pool.invokeAll(posts)) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)).withRequestBody(containing("SLOW"))));
    }
}
