package com.elvischang.ailinebot.delivery;

import com.elvischang.ailinebot.config.BotProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.ReplyMessageResponse;
import com.linecorp.bot.messaging.model.SentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

// JDK HttpClient never resends a POST, so an unclear result stays unclear instead of becoming a duplicate.
@Component
public class LineReplyClient {

    private static final Logger log = LoggerFactory.getLogger(LineReplyClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    public enum Status { SENT, REJECTED, UNKNOWN }

    public record ReplyResult(Status status, List<SentMessage> sentMessages, int httpCode) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ObjectMapper mapper = ModelObjectMapper.createNewObjectMapper();
    private final URI replyUri;
    private final String channelToken;
    private final BotProperties props;

    public LineReplyClient(@Value("${line.bot.api-end-point:https://api.line.me/}") URI apiEndPoint,
                           @Value("${line.bot.channel-token}") String channelToken,
                           BotProperties props) {
        if (channelToken == null || channelToken.isBlank()) {
            throw new IllegalStateException("line.bot.channel-token is blank");
        }
        this.replyUri = apiEndPoint.resolve("v2/bot/message/reply");
        this.channelToken = channelToken;
        this.props = props;
    }

    public ReplyResult reply(ReplyMessageRequest request) {
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(replyUri)
                    .timeout(props.replyTimeout())
                    .header("Authorization", "Bearer " + channelToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("reply request could not be serialized", e);
            return new ReplyResult(Status.REJECTED, List.of(), 0);
        }
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return new ReplyResult(Status.SENT, parseSent(response.body()), code);
            }
            if (code >= 400 && code < 500) {
                return new ReplyResult(Status.REJECTED, List.of(), code);
            }
            return new ReplyResult(Status.UNKNOWN, List.of(), code);
        } catch (IOException e) {
            log.warn("reply result unknown cause={}", e.getClass().getSimpleName());
            return new ReplyResult(Status.UNKNOWN, List.of(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ReplyResult(Status.UNKNOWN, List.of(), 0);
        }
    }

    private List<SentMessage> parseSent(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            ReplyMessageResponse parsed = mapper.readValue(body, ReplyMessageResponse.class);
            return parsed.sentMessages() == null ? List.of() : parsed.sentMessages();
        } catch (JsonProcessingException e) {
            log.warn("reply accepted but body could not be parsed");
            return List.of();
        }
    }
}
