package com.elvischang.ailinebot.webhook;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackBodyLimitFilterTest {

    private static final int LIMIT = 1024 * 1024;

    private final CallbackBodyLimitFilter filter = new CallbackBodyLimitFilter();

    // Mimics a chunked request: the body is there but no Content-Length was sent.
    private static MockHttpServletRequest chunked(String uri, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }

    private static byte[] downstreamBody(MockFilterChain chain) throws Exception {
        ServletRequest passed = chain.getRequest();
        return passed == null ? null : passed.getInputStream().readAllBytes();
    }

    @Test
    void aChunkedBodyWithinTheLimitIsPassedOnIntact() throws Exception {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chunked("/callback", body), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(downstreamBody(chain)).isEqualTo(body);
    }

    @Test
    void aChunkedBodyOverTheLimitIsRejected() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chunked("/callback", new byte[LIMIT + 1]), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void pathTricksDoNotSkipTheLimit() throws Exception {
        for (String uri : new String[]{"/callback;x", "/%63allback", "/callback/"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(chunked(uri, new byte[LIMIT + 1]), response, chain);

            assertThat(response.getStatus()).as(uri).isEqualTo(413);
        }
    }
}
