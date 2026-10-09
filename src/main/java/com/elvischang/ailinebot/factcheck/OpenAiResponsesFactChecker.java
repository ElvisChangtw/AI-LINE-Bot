package com.elvischang.ailinebot.factcheck;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
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

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class OpenAiResponsesFactChecker implements FactChecker {

    private static final int MAX_CLAIMS = 3;
    private static final String IMAGE_PLACEHOLDER = "（素材是一張圖片）";

    private final OpenAIClient client;
    private final FactCheckProperties props;
    private final FactCheckPrompt prompt;
    private final ResponseFormatTextJsonSchemaConfig format;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    public OpenAiResponsesFactChecker(FactCheckProperties props, FactCheckPrompt prompt) {
        this.props = props;
        this.prompt = prompt;
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
        Response response = client.responses().create(params(input));
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
                                .forEach(a -> consulted.add(a.asUrlCitation().url()));
                    }
                }
            }
        }
        long in = response.usage().map(u -> u.inputTokens()).orElse(0L);
        long out = response.usage().map(u -> u.outputTokens()).orElse(0L);
        return new ModelCheck(parse(text.toString()), Set.copyOf(consulted), new Usage(in, out, searchCalls));
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
        String material = input.materialText() == null ? IMAGE_PLACEHOLDER : input.materialText()
                .replace("<<<素材開始>>>", "＜＜＜素材開始＞＞＞")
                .replace("<<<素材結束>>>", "＜＜＜素材結束＞＞＞");
        String question = input.userQuestion() == null || input.userQuestion().isBlank() ? "這是真的嗎？" : input.userQuestion();
        return "使用者的問題：" + question + "\n"
                + "今天日期：" + input.today() + "（台灣時間）\n"
                + "<<<素材開始>>>\n" + material + "\n<<<素材結束>>>";
    }

    private static List<String> searchSources(ResponseFunctionWebSearch call) {
        var action = call.action();
        if (!action.isSearch()) {
            return List.of();
        }
        return action.asSearch().sources().orElse(List.of()).stream()
                .map(ResponseFunctionWebSearch.Action.Search.Source::url)
                .toList();
    }

    private FactCheckResult parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new InvalidModelOutputException("output is not JSON");
        }
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
        return new FactCheckResult(overall, bool(root, "political"), text(root, "headline"),
                List.copyOf(claims), bool(root, "safety_tip"), List.copyOf(sources));
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
