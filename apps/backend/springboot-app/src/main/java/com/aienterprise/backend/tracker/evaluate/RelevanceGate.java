package com.aienterprise.backend.tracker.evaluate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.ArticleRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class RelevanceGate {

    private static final Logger log = LoggerFactory.getLogger(RelevanceGate.class);
    private static final int MAX_EXCERPT_CHARS = 1_000;

    private final AnthropicClient client;
    private final CostGuard costGuard;
    private final TrackerRepository repository;
    private final String model;
    private final String systemPrompt;

    public RelevanceGate(
            AnthropicClient client,
            CostGuard costGuard,
            TrackerRepository repository,
            @Value("${tracker.gate-model:claude-haiku-4-5-20251001}") String model) {
        this.client = client;
        this.costGuard = costGuard;
        this.repository = repository;
        this.model = model;
        this.systemPrompt = loadPrompt();
    }

    public boolean relevant(ArticleRow article) {
        String body = article.body() == null ? "" : article.body();
        String excerpt = body.substring(0, Math.min(MAX_EXCERPT_CHARS, body.length()));
        String user = "Title: " + nullToEmpty(article.title()) + "\nExcerpt: " + excerpt;
        String response = client.complete(model, systemPrompt, user, 8);
        return response.stripLeading().toUpperCase(Locale.ROOT).startsWith("YES");
    }

    public void process(ArticleRow article) {
        if (!costGuard.allow()) {
            return;
        }
        try {
            repository.updateArticleStatus(
                    article.id(),
                    relevant(article) ? "GATE_PASSED" : "GATE_REJECTED");
        } catch (CostLimitExceededException limitReachedBetweenChecks) {
            // Deliberately leave INGESTED for the next UTC-day run.
        }
    }

    @Scheduled(cron = "${tracker.gate-cron:0 20 * * * *}")
    @SchedulerLock(name = "tracker-relevance-gate", lockAtLeastFor = "PT1M")
    public void runOnce() {
        for (ArticleRow article : repository.findByStatus("INGESTED", 100)) {
            try {
                process(article);
            } catch (RuntimeException e) {
                log.warn("relevance gate failed for article {}: {}", article.id(), e.toString());
            }
        }
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource("tracker/prompt-gate.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load tracker relevance prompt", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
