package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.service.ImageGenerationService;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.JobStatus;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import com.elvischang.ailinebot.material.RecentMaterialPicker;
import com.elvischang.ailinebot.support.RecordingFactCheckService;
import com.elvischang.ailinebot.factcheck.InvalidModelOutputException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupMention;
import static com.elvischang.ailinebot.support.Webhooks.groupText;
import static com.elvischang.ailinebot.support.Webhooks.groupTextFrom;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
    void aBareMentionChecksTheLatestRunOfMessagesFromOneSender() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "98", "第一則"), groupText("e2", "Cg1", "99", "第二則")));
        postWebhook(body(groupMention("e3", "Cg1", "100", "", null)));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).containsExactly("99");
        assertThat(factChecks.materialTexts()).containsExactly("第一則\n第二則");
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", containing("「第一則 第二則」那則"))));
    }

    @Test
    void aQuestionAboutAnUnquotedMessageLooksBackAndSaysWhichOne() throws Exception {
        stubReplyOk("900");
        LINE.stubFor(get(urlEqualTo("/v2/bot/group/Cg1/member/Uuser"))
                .willReturn(okJson("{\"displayName\":\"媽媽\\nhttps://x.example\",\"userId\":\"Uuser\"}")));
        factChecks.askForMaterialWhenAlone();
        postWebhook(body(groupTextFrom("e0", "Cg1", "97", "別人的訊息", "Uother"),
                groupText("e1", "Cg1", "98", "健保署公告"), groupText("e2", "Cg1", "99", "看到不要點")));
        postWebhook(body(groupMention("e3", "Cg1", "100", "這個要轉傳給大家嗎", null)));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).containsExactly(RecordingFactCheckService.QUESTION_ONLY, "99");
        assertThat(factChecks.materialTexts()).containsExactly("健保署公告\n看到不要點");
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", containing("（我看的是 媽媽 ")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", containing("傳的「健保署公告 看到不要點」")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", containing("TEST RESULT 99"))));
    }

    @Test
    void followUpsAboutTheBotsOwnReplyGetThatReplyAsContext() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));
        awaitSendState("100", SendState.REPLIED);
        factChecks.askForMaterialWhenAlone();

        postWebhook(body(groupMention("e3", "Cg1", "101", "為什麼說是假的", "900")));
        awaitSendState("101", SendState.REPLIED);
        postWebhook(body(groupMention("e4", "Cg1", "102", "那為何你還說是假消息", null)));
        awaitSendState("102", SendState.REPLIED);

        assertThat(factChecks.calls()).containsExactly("99", "900", RecordingFactCheckService.QUESTION_ONLY, "900");
        assertThat(factChecks.materialTexts()).containsExactly("轉傳的訊息",
                RecentMaterialPicker.EARLIER_BOT_REPLY + "TEST RESULT 99", RecentMaterialPicker.EARLIER_BOT_REPLY + "TEST RESULT 99");
    }

    @Test
    void aLookBackTheModelStillCannotUseAsksToQuoteWithoutRetrying() throws Exception {
        stubReplyOk("900");
        factChecks.askForMaterialWhenAlone();
        factChecks.behave(m -> {
            throw new InvalidModelOutputException("asked for material that was already sent");
        });
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", null)));

        awaitSendState("100", SendState.REPLIED);
        assertThat(jobFor("100").attempts()).isEqualTo(1);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.ASK_TO_QUOTE))));
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
