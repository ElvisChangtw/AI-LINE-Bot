package com.elvischang.ailinebot.factcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

// Costs real money; run only with approval: mvn test -Dgroups=live -Dtest.excludedGroups=none -Dtest=EvalRunTest -Deval.model=<model id>
@Tag("live")
class EvalRunTest {

    private static final String DEFAULT_MODEL = "gpt-6-luna";
    private static final String QUESTION = "這是真的嗎";

    @Test
    void runSamples() throws Exception {
        FactCheckProperties props = OpenAiLiveSpikeTest.liveProps(System.getProperty("eval.model", DEFAULT_MODEL));
        AtomicLong billed = new AtomicLong();
        SpendRepository spend = mock(SpendRepository.class);
        doAnswer(invocation -> {
            billed.addAndGet(invocation.<Long>getArgument(1));
            return null;
        }).when(spend).record(anyLong(), anyLong());
        OpenAiResponsesFactChecker checker = new OpenAiResponsesFactChecker(props, new FactCheckPrompt(props),
                spend, Clock.systemUTC());
        JsonNode samples = new ObjectMapper().readTree(new ClassPathResource("eval/samples.json").getInputStream());
        StringBuilder table = new StringBuilder("| id | category | expected | actual | match | searches | costMicroUsd |\n|---|---|---|---|---|---|---|\n");
        int checked = 0;
        StringBuilder replies = new StringBuilder();
        int matches = 0;
        int errors = 0;
        String only = System.getProperty("eval.ids", "");
        for (JsonNode sample : samples) {
            String id = sample.get("id").asText();
            if (!only.isEmpty() && !java.util.List.of(only.split(",")).contains(id)) {
                continue;
            }
            String expected = expected(sample);
            String actual;
            int searches = 0;
            String reply;
            long billedBefore = billed.get();
            try {
                String text = sample.hasNonNull("text") ? sample.get("text").asText() : null;
                String question = sample.hasNonNull("question") ? sample.get("question").asText() : QUESTION;
                ModelCheck check = checker.check(new FactCheckInput(text, null, null, question, LocalDate.now()));
                FactCheckResult filtered = CitationFilter.apply(check.result(), check.consultedUrls());
                actual = actual(sample, filtered);
                searches = check.usage().searchCalls();
                reply = ReplyFormatter.format(filtered, false);
            } catch (RuntimeException e) {
                System.err.println("eval " + id + " failed: " + e);
                errors++;
                actual = "ERROR:" + e.getClass().getSimpleName();
                reply = actual;
            }
            boolean match = actual.equals(expected);
            long cost = billed.get() - billedBefore;
            checked += expected.isEmpty() ? 0 : 1;
            matches += match && !expected.isEmpty() ? 1 : 0;
            table.append(String.format("| %s | %s | %s | %s | %s | %d | %d |%n", id, sample.get("category").asText(),
                    expected, actual, match ? "✓" : "✗", searches, cost));
            replies.append("### ").append(id).append("\n\n```\n").append(reply).append("\n```\n\n");
        }
        table.append(String.format("%nmodel=%s matches=%d/%d errors=%d totalCostMicroUsd=%d%n%n", props.model(), matches, checked, errors, billed.get()));
        Path out = Path.of("target", "eval-" + props.model() + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table.append(replies).toString(), StandardCharsets.UTF_8);
        System.out.println("eval report: " + out.toAbsolutePath());
    }

    // Each sample states only what it checks: a verdict, a reply mode, a high-risk flag, or a mix.
    private static String expected(JsonNode sample) {
        StringBuilder expected = new StringBuilder();
        if (sample.hasNonNull("expected")) {
            expected.append(sample.get("expected").asText());
        }
        if (sample.hasNonNull("expected_mode")) {
            expected.append(" mode=").append(sample.get("expected_mode").asText());
        }
        if (sample.hasNonNull("expected_high_risk")) {
            expected.append(" highRisk=").append(sample.get("expected_high_risk").asBoolean());
        }
        return expected.toString().trim();
    }

    private static String actual(JsonNode sample, FactCheckResult result) {
        StringBuilder actual = new StringBuilder();
        if (sample.hasNonNull("expected")) {
            actual.append(result.overall().name());
        }
        if (sample.hasNonNull("expected_mode")) {
            actual.append(" mode=").append(result.mode().name());
        }
        if (sample.hasNonNull("expected_high_risk")) {
            actual.append(" highRisk=").append(result.highRisk());
        }
        String checked = actual.toString().trim();
        return checked.isEmpty() ? "mode=" + result.mode().name() + " highRisk=" + result.highRisk() : checked;
    }
}
