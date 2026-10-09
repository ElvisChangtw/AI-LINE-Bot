package com.elvischang.ailinebot.factcheck;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FactCheckPrompt {

    private final String instructions;
    private final Map<String, Object> schema;

    public FactCheckPrompt(FactCheckProperties props) {
        String version = props.promptVersion();
        this.instructions = read("prompts/fact-check-" + version + ".md");
        try {
            this.schema = new ObjectMapper().readValue(read("prompts/fact-check-schema.json"),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("fact-check schema is not valid JSON", e);
        }
    }

    public String instructions() {
        return instructions;
    }

    public Map<String, Object> schema() {
        return schema;
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing resource " + path, e);
        }
    }
}
