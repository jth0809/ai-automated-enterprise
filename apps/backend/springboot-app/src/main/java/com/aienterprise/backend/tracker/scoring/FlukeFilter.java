package com.aienterprise.backend.tracker.scoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.evaluate.AnthropicClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Second-context fluke filter (WP1.6): a fresh Anthropic request with a fixed
 * system prompt tests whether the verified article text supports the exact
 * registered claim. The forced tool output carries only a verdict and an
 * exact quote; the quote is re-verified in code with the same
 * whitespace-normalized substring rule as the deep classifier. An invalid
 * output is an IllegalArgumentException — a failed attempt, never a verdict.
 */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class FlukeFilter {

    static final String TOOL_NAME = "review_claim";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VERDICTS = Set.of("MATCH", "MISMATCH");

    private final AnthropicClient client;
    private final String model;
    private final List<Map<String, Object>> systemBlocks;
    private final Map<String, Object> toolSchema;
    private final String promptSha256;

    public FlukeFilter(
            AnthropicClient client,
            @Value("${tracker.fluke-model:${tracker.classify-model:claude-opus-4-8}}") String model) {
        this.client = client;
        this.model = model;
        String prompt = loadResource("tracker/prompt-fluke-system.txt");
        this.systemBlocks = List.of(Map.of(
                "type", "text",
                "text", prompt,
                "cache_control", Map.of("type", "ephemeral")));
        this.toolSchema = parseSchema(loadResource("tracker/fluke-tool-schema.json"));
        this.promptSha256 = sha256(prompt);
    }

    public FlukeResult evaluate(FlukeCandidate candidate) {
        Map<String, Object> output = client.completeWithTool(
                model, systemBlocks, userMessage(candidate), toolSchema, TOOL_NAME);
        String verdict = str(output.get("verdict"));
        String quote = str(output.get("evidence_quote"));
        if (verdict == null || !VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("Fluke filter returned an unknown verdict: " + verdict);
        }
        if (quote == null) {
            throw new IllegalArgumentException("Fluke filter returned no evidence quote");
        }
        String normalizedQuote = normalize(quote);
        boolean quoteFound = candidate.articleBodies().stream()
                .anyMatch(body -> body != null && normalize(body).contains(normalizedQuote));
        if (!quoteFound) {
            throw new IllegalArgumentException("Fluke evidence quote is not in any supplied body");
        }
        return new FlukeResult(verdict, quote, toJson(output), model, promptSha256);
    }

    public String promptSha256() {
        return promptSha256;
    }

    private static String userMessage(FlukeCandidate candidate) {
        StringBuilder message = new StringBuilder();
        message.append("Registered node: ").append(candidate.nodeCode())
                .append(" — ").append(candidate.nodeDefinition())
                .append(" (scale ").append(candidate.scaleType())
                .append(", current level: ").append(candidate.currentLevel()).append(")\n\n");
        message.append("Candidate claim: ").append(candidate.eventType())
                .append(", claimed level ").append(candidate.claimedLevel())
                .append(", actor ").append(candidate.actor())
                .append(", occurred on ").append(candidate.occurredOn()).append("\n\n");
        int index = 1;
        for (String body : candidate.articleBodies()) {
            message.append("Article ").append(index++).append(":\n").append(body).append("\n\n");
        }
        return message.toString();
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String toJson(Map<String, Object> output) {
        try {
            return JSON.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize fluke tool output", e);
        }
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse fluke tool schema", e);
        }
    }

    private static String loadResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load tracker resource " + path, e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
