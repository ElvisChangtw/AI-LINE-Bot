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

import static org.mockito.Mockito.mock;

// Costs real money; run only with approval: mvn test -Dgroups=live -Dtest.excludedGroups=none -Dtest=EvalRunTest -Deval.model=<model id>
@Tag("live")
class EvalRunTest {

    private static final String DEFAULT_MODEL = "gpt-6-luna";
    private static final String QUESTION = "這是真的嗎";

    @Test
    void runSamples() throws Exception {
        FactCheckProperties props = OpenAiLiveSpikeTest.liveProps(System.getProperty("eval.model", DEFAULT_MODEL));
        OpenAiResponsesFactChecker checker = new OpenAiResponsesFactChecker(props, new FactCheckPrompt(props),
                mock(SpendRepository.class), Clock.systemUTC());
        JsonNode samples = new ObjectMapper().readTree(new ClassPathResource("eval/samples.json").getInputStream());
        StringBuilder table = new StringBuilder("| id | category | expected | actual | match | searches | costMicroUsd |\n|---|---|---|---|---|---|---|\n");
        StringBuilder replies = new StringBuilder();
        long totalCost = 0;
        int matches = 0;
        for (JsonNode sample : samples) {
            String id = sample.get("id").asText();
            String expected = sample.get("expected").asText();
            String actual;
            long cost = 0;
            int searches = 0;
            String reply;
            try {
                ModelCheck check = checker.check(new FactCheckInput(sample.get("text").asText(), null, null, QUESTION, LocalDate.now()));
                FactCheckResult filtered = CitationFilter.apply(check.result(), check.consultedUrls());
                actual = filtered.overall().name();
                cost = check.usage().costMicroUsd(props);
                searches = check.usage().searchCalls();
                reply = ReplyFormatter.format(filtered, false);
            } catch (RuntimeException e) {
                actual = "ERROR:" + e.getClass().getSimpleName();
                reply = actual;
            }
            boolean match = actual.equals(expected);
            matches += match ? 1 : 0;
            totalCost += cost;
            table.append(String.format("| %s | %s | %s | %s | %s | %d | %d |%n", id, sample.get("category").asText(),
                    expected, actual, match ? "✓" : "✗", searches, cost));
            replies.append("### ").append(id).append("\n\n```\n").append(reply).append("\n```\n\n");
        }
        table.append(String.format("%nmodel=%s matches=%d/%d totalCostMicroUsd=%d%n%n", props.model(), matches, samples.size(), totalCost));
        Path out = Path.of("target", "eval-" + props.model() + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table.append(replies).toString(), StandardCharsets.UTF_8);
        System.out.println("eval report: " + out.toAbsolutePath());
    }
}
