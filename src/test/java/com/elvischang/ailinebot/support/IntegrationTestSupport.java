package com.elvischang.ailinebot.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestBeans.class)
public abstract class IntegrationTestSupport {

    protected static final WireMockServer LINE = new WireMockServer(options().dynamicPort());

    static {
        LINE.start();
    }

    @DynamicPropertySource
    static void lineEndpoints(DynamicPropertyRegistry registry) {
        registry.add("line.bot.api-end-point", () -> LINE.baseUrl() + "/");
        registry.add("line.bot.blob-end-point", () -> LINE.baseUrl() + "/");
        registry.add("factcheck.base-url", () -> LINE.baseUrl() + "/v1");
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected RecordingFactCheckService factChecks;

    @BeforeEach
    void resetSharedState() {
        jdbc.sql("DELETE FROM job").update();
        jdbc.sql("DELETE FROM material").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        LINE.resetAll();
        clock.set(TestBeans.START);
        factChecks.reset();
    }

    @AfterEach
    void neverPush() {
        LINE.verify(0, postRequestedFor(urlPathMatching("/v2/bot/message/(push|multicast|broadcast|narrowcast).*")));
    }

    protected static void stubReplyOk(String sentMessageId) {
        LINE.stubFor(post(urlEqualTo("/v2/bot/message/reply")).atPriority(5)
                .willReturn(okJson("{\"sentMessages\":[{\"id\":\"" + sentMessageId + "\",\"quoteToken\":\"q" + sentMessageId + "\"}]}")));
    }

    protected void postWebhook(String body) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", Webhooks.sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }
}
