package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.service.ImageGenerationService;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.JobStatus;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import com.elvischang.ailinebot.support.RecordingFactCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupMention;
import static com.elvischang.ailinebot.support.Webhooks.groupText;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class JobFlowTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";

    @Autowired
    JobRepository jobs;

    @Autowired
    JobRunner runner;

    @MockBean
    ImageGenerationService imageGenerationService;

    private Job jobFor(String triggerMessageId) {
        String id = jdbc.sql("SELECT id FROM job WHERE trigger_message_id = :m").param("m", triggerMessageId)
                .query(String.class).single();
        return jobs.find(id).orElseThrow();
    }

    private void awaitSendState(String triggerMessageId, SendState expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobFor(triggerMessageId).sendState()).isEqualTo(expected));
    }

    @Test
    void checksTheQuotedMaterialAndReplies() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).containsExactly("99");
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-e2")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo("TEST RESULT 99"))));
    }

    @Test
    void anUnquotedQuestionIsAnsweredWithoutMaterial() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "99", "家人剛傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "明天台北會下雨嗎", null)));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).containsExactly(RecordingFactCheckService.QUESTION_ONLY);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo("TEST RESULT question"))));
    }

    @Test
    void ambiguousMentionGetsANoticeWithoutCheckingAnything() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "98", "第一則"), groupText("e2", "Cg1", "99", "第二則")));
        postWebhook(body(groupMention("e3", "Cg1", "100", "", null)));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).isEmpty();
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.TOO_MANY_CANDIDATES))));
    }

    @Test
    void slowResultRidesTheNextPlainMessage() throws Exception {
        stubReplyOk("900");
        factChecks.behave(m -> {
            clock.advance(Duration.ofSeconds(60));
            return "SLOW " + m.messageId();
        });
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));
        awaitSendState("100", SendState.WAITING_NEXT);
        LINE.verify(0, postRequestedFor(urlEqualTo(REPLY)));

        postWebhook(body(groupText("e3", "Cg1", "101", "早安")));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).containsExactly("99");
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-e3")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.DELAYED_PREFIX + "SLOW 99")))
                .withRequestBody(matchingJsonPath("$.messages[0].quoteToken", equalTo("qt-100"))));
    }

    @Test
    void nextMentionRepliesWithItsOwnAnswerAndTheWaitingOne() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "97", "第一則"), groupText("e2", "Cg1", "99", "第二則")));
        factChecks.behave(m -> {
            clock.advance(Duration.ofSeconds(60));
            return "SLOW " + m.messageId();
        });
        postWebhook(body(groupMention("e3", "Cg1", "102", "這個呢", "97")));
        awaitSendState("102", SendState.WAITING_NEXT);

        factChecks.reset();
        postWebhook(body(groupMention("e4", "Cg1", "103", "真的嗎", "99")));

        awaitSendState("103", SendState.REPLIED);
        assertThat(jobFor("102").sendState()).isEqualTo(SendState.REPLIED);
        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-e4")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo("TEST RESULT 99")))
                .withRequestBody(matchingJsonPath("$.messages[1].text", equalTo(BotReplies.DELAYED_PREFIX + "SLOW 97"))));
    }

    @Test
    void failingCheckIsRetriedThenSendsAFailureNotice() throws Exception {
        stubReplyOk("900");
        factChecks.behave(m -> {
            throw new IllegalStateException("model down");
        });
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Job job = jobFor("100");
            assertThat(job.status()).isEqualTo(JobStatus.PENDING);
            assertThat(job.attempts()).isEqualTo(1);
        });

        runner.run(jobFor("100").id());

        Job job = jobFor("100");
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.sendState()).isEqualTo(SendState.REPLIED);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.JOB_FAILED))));
    }

    @Test
    void imagineRepliesWithTheGeneratedImage() throws Exception {
        stubReplyOk("900");
        when(imageGenerationService.generateImage(anyString())).thenReturn("https://img.example/cat.png");
        postWebhook(body(groupMention("e1", "Cg1", "100", "/imagine 一隻貓", null)));

        awaitSendState("100", SendState.REPLIED);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].type", equalTo("image"))));
    }
}
