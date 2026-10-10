package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupMention;
import static com.elvischang.ailinebot.support.Webhooks.groupText;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FactCheckAcceptanceTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";
    private static final String RESPONSES = "/v1/responses";

    private static final String EXPECTED_VACCINE_REPLY = """
            這是假消息，2021 年就在流傳的舊謠言
            ・電話裡按 1 或按 2，不會讓手機被控制
            ・疾管署說，他們不會打電話問你有沒有打疫苗
            不要點連結、不要給驗證碼和帳號密碼，不放心可以打 165 問
            不用擔心，這則可以不用再轉傳囉
            來源：台灣事實查核中心
            https://tfc-taiwan.org.tw/?p=108215""";

    @Autowired
    DefaultFactCheckService realService;

    @Autowired
    SpendRepository spendRepository;

    @BeforeEach
    void useRealPipeline() throws Exception {
        factChecks.delegateTo(realService);
        stubReplyOk("900");
        LINE.stubFor(post(urlEqualTo(RESPONSES)).willReturn(okJson(
                new ClassPathResource("openai/response-vaccine.json").getContentAsString(StandardCharsets.UTF_8))));
    }

    private SendState sendStateOf(String trigger) {
        return jdbc.sql("SELECT send_state FROM job WHERE trigger_message_id = :m").param("m", trigger)
                .query(String.class).optional().map(SendState::valueOf).orElse(null);
    }

    @Test
    void quotedRumourGetsAFormattedAnswerWithOnlyRealSources() throws Exception {
        postWebhook(body(groupText("e1", "Cg1", "99", "接到電話按1手機就被屏蔽")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));

        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(EXPECTED_VACCINE_REPLY))));
        assertThat(jdbc.sql("SELECT verdict || '/' || search_calls || '/' || cost_micro_usd FROM job WHERE trigger_message_id = '100'")
                .query(String.class).single()).isEqualTo("INCORRECT/1/14000");
    }

    @Test
    void theSameRumourInAnotherGroupIsServedFromCache() throws Exception {
        postWebhook(body(groupText("e1", "Cg1", "99", "接到電話按1手機就被屏蔽")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));
        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);

        postWebhook(body(groupText("e3", "Cg2", "199", "接到電話按1手機就被屏蔽")));
        postWebhook(body(groupMention("e4", "Cg2", "200", "這是真的嗎", "199")));
        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("200") == SendState.REPLIED);

        LINE.verify(1, postRequestedFor(urlEqualTo(RESPONSES)));
        LINE.verify(2, postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(EXPECTED_VACCINE_REPLY))));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM verification_cache").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT name FROM pragma_table_info('verification_cache') ORDER BY cid").query(String.class).list())
                .containsExactly("content_hash", "result_json", "created_at", "expires_at");
    }

    @Test
    void spentBudgetGetsTheQuotaNoticeWithoutCallingOpenAi() throws Exception {
        spendRepository.record(clock.millis(), 2_000_000L);
        postWebhook(body(groupText("e1", "Cg1", "99", "接到電話按1手機就被屏蔽")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));

        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);
        LINE.verify(0, postRequestedFor(urlEqualTo(RESPONSES)));
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.QUOTA_EXCEEDED))));
    }
}
