package com.aienterprise.backend.tracker.evaluate;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class AnthropicClient {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";

    private final RestClient client;
    private final CostGuard costGuard;

    public AnthropicClient(
            @Value("${anthropic.api-key:}") String apiKey,
            CostGuard costGuard) {
        this.client = RestClient.builder()
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.costGuard = costGuard;
    }

    public String complete(String model, String system, String user, int maxTokens) {
        Map<?, ?> response = post(Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", system,
                "messages", List.of(Map.of("role", "user", "content", user))));
        recordUsage(model, response);
        Object content = response.get("content");
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    return String.valueOf(map.get("text"));
                }
            }
        }
        throw new IllegalStateException("Anthropic response contains no text block");
    }

    public Map<String, Object> completeWithTool(
            String model,
            List<Map<String, Object>> systemBlocks,
            String user,
            Map<String, Object> toolSchema,
            String toolName) {
        Map<?, ?> response = post(Map.of(
                "model", model,
                "max_tokens", 4096,
                "system", systemBlocks,
                "messages", List.of(Map.of("role", "user", "content", user)),
                "tools", List.of(Map.of(
                        "name", toolName,
                        "description", "Return structured tracker classifications",
                        "input_schema", toolSchema)),
                "tool_choice", Map.of("type", "tool", "name", toolName)));
        recordUsage(model, response);
        Object content = response.get("content");
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map
                        && "tool_use".equals(map.get("type"))
                        && toolName.equals(map.get("name"))
                        && map.get("input") instanceof Map<?, ?> input) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) input;
                    return typed;
                }
            }
        }
        throw new IllegalStateException("Anthropic response contains no requested tool_use block");
    }

    private Map<?, ?> post(Map<String, Object> request) {
        if (!costGuard.allow()) {
            throw new CostLimitExceededException();
        }
        Map<?, ?> response = client.post()
                .uri(MESSAGES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("Anthropic returned an empty response");
        }
        return response;
    }

    private void recordUsage(String model, Map<?, ?> response) {
        if (!(response.get("usage") instanceof Map<?, ?> usage)) {
            return;
        }
        costGuard.record(
                model,
                number(usage.get("input_tokens")),
                number(usage.get("output_tokens")),
                number(usage.get("cache_read_input_tokens")));
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
