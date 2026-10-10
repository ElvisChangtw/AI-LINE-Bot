package com.elvischang.ailinebot.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// The controller reads the whole body before verifying the signature, so cap it first.
@Component
public class CallbackBodyLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallbackBodyLimitFilter.class);
    private static final long MAX_BODY_BYTES = 1024 * 1024;
    private static final String CALLBACK_PATH = "/callback";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CALLBACK_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            log.warn("webhook rejected: no content length");
            response.setStatus(HttpStatus.LENGTH_REQUIRED.value());
            return;
        }
        if (length > MAX_BODY_BYTES) {
            log.warn("webhook rejected: body too large bytes={}", length);
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        chain.doFilter(request, response);
    }
}
