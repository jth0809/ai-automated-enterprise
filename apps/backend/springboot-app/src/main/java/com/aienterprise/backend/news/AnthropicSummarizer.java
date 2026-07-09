package com.aienterprise.backend.news;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Claude-backed {@link ArticleSummarizer}: posts the article to the Anthropic
 * Messages API and attaches the 1-2 sentence summary it returns. Any failure
 * (HTTP error, timeout, unexpected payload) degrades gracefully to the
 * unchanged article — the summary stays null and the UI falls back to the
 * source excerpt, matching the {@link DisabledSummarizer} contract. In-cluster
 * this egress must be whitelisted in the backend CiliumNetworkPolicy
 * (api.anthropic.com:443).
 */
public class AnthropicSummarizer implements ArticleSummarizer {

    static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 1024;

    private static final Logger log = LoggerFactory.getLogger(AnthropicSummarizer.class);

    private final RestClient client;
    private final String model;

    public AnthropicSummarizer(RestClient.Builder builder, String apiKey, String model) {
        this.client = builder
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .build();
        this.model = model;
    }

    @Override
    public Article summarize(Article article) {
        try {
            Map<?, ?> response = client.post()
                    .uri(MESSAGES_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "max_tokens", MAX_TOKENS,
                            "messages", List.of(Map.of(
                                    "role", "user",
                                    "content", prompt(article)))))
                    .retrieve()
                    .body(Map.class);
            String summary = firstTextBlock(response);
            return summary.isBlank() ? article : article.withSummary(summary);
        } catch (RuntimeException e) {
            log.warn("Anthropic summarization failed for {}: {}", article.link(), e.getMessage());
            return article;
        }
    }

    private static String prompt(Article article) {
        StringBuilder sb = new StringBuilder(
                "Summarize this news article in 1-2 plain-text sentences. Respond with the summary only.");
        if (article.title() != null && !article.title().isBlank()) {
            sb.append("\n\nTitle: ").append(article.title());
        }
        if (article.excerpt() != null && !article.excerpt().isBlank()) {
            sb.append("\n\n").append(article.excerpt());
        }
        return sb.toString();
    }

    /** Text of the first {@code type=text} content block; empty when absent. */
    private static String firstTextBlock(Map<?, ?> response) {
        if (response != null && response.get("content") instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    Object text = map.get("text");
                    return text == null ? "" : text.toString().trim();
                }
            }
        }
        return "";
    }
}
