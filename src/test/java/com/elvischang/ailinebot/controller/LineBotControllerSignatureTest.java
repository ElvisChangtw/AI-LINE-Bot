package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import com.elvischang.ailinebot.support.Webhooks;
import com.elvischang.ailinebot.webhook.WebhookIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LineBotControllerSignatureTest extends IntegrationTestSupport {

    @MockBean
    WebhookIngestService ingestService;

    private final String body = Webhooks.body(Webhooks.groupText("e1", "Cg1", "100", "hello"));

    @Test
    void rejectsMissingSignature() throws Exception {
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestService);
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", "bogus").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestService);
    }

    @Test
    void acceptsSignedRequest() throws Exception {
        postWebhook(body);
        verify(ingestService).ingest(any());
    }

    @Test
    void acceptsLineConsoleVerifyRequestWithNoEvents() throws Exception {
        postWebhook(Webhooks.body());
        verify(ingestService).ingest(List.of());
    }

    @Test
    void rejectsSignedBodyThatIsNotJson() throws Exception {
        String notJson = "not json";
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", Webhooks.sign(notJson)).content(notJson))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestService);
    }

    @Test
    void rejectsValidSignatureOfADifferentBody() throws Exception {
        String otherBody = "{\"destination\":\"Ubot\",\"events\":[]}";
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", Webhooks.sign(otherBody)).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestService);
    }

    @Test
    void rejectsSignedEmptyObject() throws Exception {
        String empty = "{}";
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", Webhooks.sign(empty)).content(empty))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestService);
    }
}
