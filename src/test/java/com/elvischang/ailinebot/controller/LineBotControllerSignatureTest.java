package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.service.LineMessageService;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import com.elvischang.ailinebot.support.Webhooks;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LineBotControllerSignatureTest extends IntegrationTestSupport {

    @MockBean
    LineMessageService lineMessageService;

    private final String body = Webhooks.body(Webhooks.groupText("e1", "Cg1", "100", "hello"));

    @Test
    void rejectsMissingSignature() throws Exception {
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lineMessageService);
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", "bogus").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lineMessageService);
    }

    @Test
    void acceptsSignedRequest() throws Exception {
        postWebhook(body);
        verify(lineMessageService).handleTextMessage(any());
    }

    @Test
    void acceptsLineConsoleVerifyRequestWithNoEvents() throws Exception {
        postWebhook(Webhooks.body());
        verifyNoInteractions(lineMessageService);
    }

    @Test
    void rejectsSignedBodyThatIsNotJson() throws Exception {
        String notJson = "not json";
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", Webhooks.sign(notJson)).content(notJson))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lineMessageService);
    }
}
