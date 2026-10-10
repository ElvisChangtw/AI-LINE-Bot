package com.elvischang.ailinebot.factcheck;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionWebSearch;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.WebSearchTool;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class OpenAiResponsesFactChecker implements FactChecker {

    private static final int MAX_CLAIMS = 3;
    private static final int MAX_ANSWER_POINTS = 2;
    private static final String NO_QUESTION = "使用者沒有寫問題，請查證下面的素材";
    private static final List<String> DELIMITERS = List.of("<<<問題開始>>>", "<<<問題結束>>>", "<<<素材開始>>>", "<<<素材結束>>>");
    private static final long ESTIMATED_FAILURE_INPUT_TOKENS = 20_000;
    private static final long ESTIMATED_FAILURE_OUTPUT_TOKENS = 4_000;
    private static final int FIRST_SERVER_ERROR_STATUS = 500;
    private static final String INSUFFICIENT_QUOTA = "insufficient_quota";
    private static final String IMAGE_PLACEHOLDER = "（素材是一張圖片）";

    private final OpenAIClient client;
    private final FactCheckProperties props;
    private final FactCheckPrompt prompt;
    private final SpendRepository spending;
    private final Clock clock;
    private final ResponseFormatTextJsonSchemaConfig format;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    public OpenAiResponsesFactChecker(FactCheckProperties props, FactCheckPrompt prompt,
                                    SpendRepository spending, Clock clock) {
        this.props = props;
        this.prompt = prompt;
        this.spending = spending;
        this.clock = clock;
        this.client = OpenAIOkHttpClient.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .timeout(props.requestTimeout())
                .maxRetries(0)
                .build();
        ResponseFormatTextJsonSchemaConfig.Schema.Builder schema = ResponseFormatTextJsonSchemaConfig.Schema.builder();
        prompt.schema().forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        this.format = ResponseFormatTextJsonSchemaConfig.builder()
                .name("fact_check")
                .schema(schema.build())
                .strict(true)
                .build();
    }

    @PreDestroy
    void close() {
        client.close();
    }

    @Override
    public ModelCheck check(FactCheckInput input) {
        Response response = create(params(input));
        StringBuilder text = new StringBuilder();
        Set<String> consulted = new LinkedHashSet<>();
        int searchCalls = 0;
        for (var item : response.output()) {
            if (item.isWebSearchCall()) {
                searchCalls++;
                consulted.addAll(searchSources(item.asWebSearchCall()));
            } else if (item.isMessage()) {
                for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                    if (content.isOutputText()) {
                        ResponseOutputText outputText = content.asOutputText();
                        text.append(outputText.text());
                        outputText.annotations().stream()
                                .filter(a -> a.isUrlCitation())
                                .forEach(a -> a.asUrlCitation()._url().asString().ifPresent(consulted::add));
                    }
                }
            }
        }
        long in = response.usage().map(u -> u.inputTokens()).orElse(0L);
        long out = response.usage().map(u -> u.outputTokens()).orElse(0L);
        Usage usage = new Usage(in, out, searchCalls);
        spending.record(clock.millis(), usage.costMicroUsd(props));
        return new ModelCheck(parse(text.toString()), Set.copyOf(consulted), usage);
    }

    private Response create(ResponseCreateParams params) {
        try {
            return client.responses().create(params);
        } catch (OpenAIIoException e) {
            spending.record(clock.millis(), estimatedFailureCostMicroUsd());
            throw e;
        } catch (OpenAIServiceException e) {
            if (e.code().filter(INSUFFICIENT_QUOTA::equals).isPresent()) {
                throw new OutOfCreditException(e);
            }
            if (e.statusCode() >= FIRST_SERVER_ERROR_STATUS) {
                spending.record(clock.millis(), estimatedFailureCostMicroUsd());
            }
            throw e;
        }
    }

    // A failed call may still have been billed, so assume a typical call that used every allowed search.
    private long estimatedFailureCostMicroUsd() {
        return new Usage(ESTIMATED_FAILURE_INPUT_TOKENS, ESTIMATED_FAILURE_OUTPUT_TOKENS, props.maxToolCalls()).costMicroUsd(props);
    }

    private ResponseCreateParams params(FactCheckInput input) {
        ResponseInputItem.Message.Builder message = ResponseInputItem.Message.builder()
                .role(ResponseInputItem.Message.Role.USER)
                .addInputTextContent(userText(input));
        if (input.image() != null) {
            message.addContent(ResponseInputImage.builder()
                    .imageUrl("data:" + input.imageMimeType() + ";base64," + Base64.getEncoder().encodeToString(input.image()))
                    .detail(ResponseInputImage.Detail.AUTO)
                    .build());
        }
        return ResponseCreateParams.builder()
                .model(props.model())
                .instructions(prompt.instructions())
                .inputOfResponse(List.of(ResponseInputItem.ofMessage(message.build())))
                .addTool(WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build())
                .maxToolCalls((long) props.maxToolCalls())
                .reasoning(Reasoning.builder().effort(ReasoningEffort.of(props.reasoningEffort())).build())
                .addInclude(ResponseIncludable.WEB_SEARCH_CALL_ACTION_SOURCES)
                .text(ResponseTextConfig.builder().format(format).build())
                .store(false)
                .build();
    }

    private static String userText(FactCheckInput input) {
        String question = input.userQuestion() == null || input.userQuestion().isBlank() ? NO_QUESTION : input.userQuestion();
        String text = "今天日期：" + input.today() + "（台灣時間）\n"
                + "<<<問題開始>>>\n" + neutralized(question) + "\n<<<問題結束>>>";
        if (input.materialText() == null && input.image() == null) {
            return text;
        }
        String material = input.materialText() == null ? IMAGE_PLACEHOLDER : neutralized(input.materialText());
        return text + "\n<<<素材開始>>>\n" + material + "\n<<<素材結束>>>";
    }

    private static String neutralized(String untrusted) {
        String result = untrusted;
        for (String delimiter : DELIMITERS) {
            result = result.replace(delimiter, delimiter.replace("<<<", "＜＜＜").replace(">>>", "＞＞＞"));
        }
        return result;
    }

    private static List<String> searchSources(ResponseFunctionWebSearch call) {
        // Failed or partial calls can come back without an action; they consulted nothing.
        var action = call._action().asKnown();
        if (action.isEmpty()) {
            return List.of();
        }
        if (action.get().isOpenPage()) {
            return action.get().asOpenPage()._url().asString().stream().toList();
        }
        if (action.get().isFind()) {
            return action.get().asFind()._url().asString().stream().toList();
        }
        if (!action.get().isSearch()) {
            return List.of();
        }
        return action.get().asSearch().sources().orElse(List.of()).stream()
                .flatMap(source -> source._url().asString().stream())
                .toList();
    }

    private FactCheckResult parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new InvalidModelOutputException("output is not JSON");
        }
        ReplyMode mode = mode(root);
        Verdict overall = verdict(root, "overall_verdict");
        List<FactCheckResult.Claim> claims = new ArrayList<>();
        for (JsonNode claim : array(root, "claims")) {
            if (claims.size() == MAX_CLAIMS) {
                break;
            }
            claims.add(new FactCheckResult.Claim(text(claim, "text"), verdict(claim, "verdict"), text(claim, "reason")));
        }
        List<FactCheckResult.Source> sources = new ArrayList<>();
        for (JsonNode source : array(root, "sources")) {
            sources.add(new FactCheckResult.Source(text(source, "url"), text(source, "title"),
                    text(source, "publisher"), text(source, "published_date")));
        }
        List<String> answerPoints = new ArrayList<>();
        for (JsonNode point : array(root, "answer_points")) {
            if (!point.isTextual()) {
                throw new InvalidModelOutputException("answer point is not text");
            }
            if (!point.asText().isBlank() && answerPoints.size() < MAX_ANSWER_POINTS) {
                answerPoints.add(point.asText());
            }
        }
        checkConsistent(mode, overall, answerPoints);
        return new FactCheckResult(overall, bool(root, "political"), text(root, "headline"),
                List.copyOf(claims), bool(root, "safety_tip"), List.copyOf(sources),
                mode, bool(root, "high_risk"), List.copyOf(answerPoints));
    }

    // The strict schema only guarantees every field exists, not that the fields agree with the mode.
    private static void checkConsistent(ReplyMode mode, Verdict overall, List<String> answerPoints) {
        if ((mode == ReplyMode.FACT_CHECK) == (overall == Verdict.NOT_APPLICABLE)) {
            throw new InvalidModelOutputException("verdict does not match mode " + mode);
        }
        if (mode == ReplyMode.ANSWER && answerPoints.isEmpty()) {
            throw new InvalidModelOutputException("answer without points");
        }
    }

    private static ReplyMode mode(JsonNode root) {
        try {
            return ReplyMode.valueOf(text(root, "mode"));
        } catch (IllegalArgumentException e) {
            throw new InvalidModelOutputException("unknown mode");
        }
    }

    private static Verdict verdict(JsonNode node, String field) {
        try {
            return Verdict.valueOf(text(node, field));
        } catch (IllegalArgumentException e) {
            throw new InvalidModelOutputException("unknown verdict in " + field);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidModelOutputException("missing text field " + field);
        }
        return value.asText();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new InvalidModelOutputException("missing boolean field " + field);
        }
        return value.asBoolean();
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new InvalidModelOutputException("missing array field " + field);
        }
        return value;
    }
}
