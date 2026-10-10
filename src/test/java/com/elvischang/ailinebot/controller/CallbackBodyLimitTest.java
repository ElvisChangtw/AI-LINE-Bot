package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import com.elvischang.ailinebot.support.Webhooks;
import com.elvischang.ailinebot.webhook.WebhookIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CallbackBodyLimitTest extends IntegrationTestSupport {

    private static final int LIMIT = 1024 * 1024;

    @MockBean
    WebhookIngestService ingestService;

    @Test
    void rejectsABodyOverOneMegabyteBeforeCheckingTheSignature() throws Exception {
        String huge = "{\"destination\":\"Ubot\",\"events\":[],\"pad\":\"" + "x".repeat(LIMIT) + "\"}";
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", Webhooks.sign(huge)).content(huge))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(ingestService);
    }

    @Test
    void anEmptyBodyStillReachesTheSignatureCheck() throws Exception {
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestService);
    }

    @Test
    void aBodyAtTheLimitStillReachesTheSignatureCheck() throws Exception {
        String atLimit = "x".repeat(LIMIT);
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", "bogus").content(atLimit))
                .andExpect(status().isBadRequest());
    }
}
