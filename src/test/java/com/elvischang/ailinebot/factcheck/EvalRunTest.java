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
        StringBuilder replies = new StringBuilder();
        int matches = 0;
        int errors = 0;
        for (JsonNode sample : samples) {
            String id = sample.get("id").asText();
            String expected = sample.get("expected").asText();
            String actual;
            int searches = 0;
            String reply;
            long billedBefore = billed.get();
            try {
                ModelCheck check = checker.check(new FactCheckInput(sample.get("text").asText(), null, null, QUESTION, LocalDate.now()));
                FactCheckResult filtered = CitationFilter.apply(check.result(), check.consultedUrls());
                actual = filtered.overall().name();
                searches = check.usage().searchCalls();
                reply = ReplyFormatter.format(filtered, false);
            } catch (RuntimeException e) {
                errors++;
                actual = "ERROR:" + e.getClass().getSimpleName();
                reply = actual;
            }
            boolean match = actual.equals(expected);
            long cost = billed.get() - billedBefore;
            matches += match ? 1 : 0;
            table.append(String.format("| %s | %s | %s | %s | %s | %d | %d |%n", id, sample.get("category").asText(),
                    expected, actual, match ? "✓" : "✗", searches, cost));
            replies.append("### ").append(id).append("\n\n```\n").append(reply).append("\n```\n\n");
        }
        table.append(String.format("%nmodel=%s matches=%d/%d errors=%d totalCostMicroUsd=%d%n%n", props.model(), matches, samples.size(), errors, billed.get()));
        Path out = Path.of("target", "eval-" + props.model() + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table.append(replies).toString(), StandardCharsets.UTF_8);
        System.out.println("eval report: " + out.toAbsolutePath());
    }
}
