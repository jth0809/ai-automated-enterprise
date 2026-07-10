package com.aienterprise.backend.news;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Claude-backed {@link TitleTranslator}: translates a bounded headline list
 * into Korean in ONE Messages API call per batch — never one call per article,
 * so a full ingest costs at most one extra request on top of the summary budget
 * (resource-constraint principle). Any failure (HTTP error, malformed reply,
 * count mismatch) degrades gracefully to the unchanged articles, matching the
 * {@link DisabledTitleTranslator} contract.
 */
public class AnthropicTitleTranslator implements TitleTranslator {

    static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_OUTPUT_TOKENS = 1024;
    private static final int OUTPUT_TOKENS_PER_TITLE = 64;
    private static final int OUTPUT_TOKEN_OVERHEAD = 32;

    private static final Logger log = LoggerFactory.getLogger(AnthropicTitleTranslator.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final RestClient client;
    private final String model;

    public AnthropicTitleTranslator(RestClient.Builder builder, String apiKey, String model) {
        this.client = builder
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .build();
        this.model = model;
    }

    @Override
    public List<Article> translateTitles(List<Article> articles) {
        if (articles.isEmpty()) {
            return articles;
        }
        try {
            Map<?, ?> response = client.post()
                    .uri(MESSAGES_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "max_tokens", maxTokens(articles.size()),
                            "messages", List.of(Map.of(
                                    "role", "user",
                                    "content", prompt(articles)))))
                    .retrieve()
                    .body(Map.class);
            String[] translations = parseTranslations(firstTextBlock(response));
            if (translations.length != articles.size()) {
                log.warn("title translation count mismatch: sent {}, got {}",
                        articles.size(), translations.length);
                return articles;
            }
            List<Article> out = new ArrayList<>(articles.size());
            for (int i = 0; i < articles.size(); i++) {
                String t = translations[i] == null ? "" : translations[i].trim();
                out.add(t.isBlank() ? articles.get(i) : articles.get(i).withTranslatedTitle(t));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Anthropic title translation failed for a batch of {}: {}",
                    articles.size(), e.getMessage());
            return articles;
        }
    }

    private static int maxTokens(int titleCount) {
        return Math.min(MAX_OUTPUT_TOKENS,
                OUTPUT_TOKEN_OVERHEAD + OUTPUT_TOKENS_PER_TITLE * titleCount);
    }

    private static String prompt(List<Article> articles) {
        StringBuilder sb = new StringBuilder(
                "Translate each of the following news headlines into natural Korean. "
                        + "Respond with ONLY a JSON array of strings — same order, same count, "
                        + "no code fences, no commentary.");
        for (Article article : articles) {
            sb.append("\n- ").append(article.title() == null ? "" : article.title());
        }
        return sb.toString();
    }

    private static String[] parseTranslations(String text) throws RuntimeException {
        // Tolerate a fenced reply even though the prompt forbids it.
        String cleaned = text.strip()
                .replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "");
        try {
            return json.readValue(cleaned, String[].class);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable translation payload", e);
        }
    }

    /** Text of the first {@code type=text} content block; empty when absent. */
    private static String firstTextBlock(Map<?, ?> response) {
        if (response != null && response.get("content") instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    Object text = map.get("text");
                    return text == null ? "" : text.toString();
                }
            }
        }
        return "";
    }
}
