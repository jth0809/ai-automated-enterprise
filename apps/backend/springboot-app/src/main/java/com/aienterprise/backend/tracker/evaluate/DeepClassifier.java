package com.aienterprise.backend.tracker.evaluate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.ArticleRow;
import com.aienterprise.backend.tracker.domain.ClassificationRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class DeepClassifier {

    public record ClaimDraft(
            String nodeCode,
            String eventType,
            Integer claimedLevel,
            String actor,
            LocalDate occurredOn,
            String publicationPath,
            String evidenceQuote,
            String duplicateHint) {
    }

    private static final Logger log = LoggerFactory.getLogger(DeepClassifier.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_NAME = "classify_article";
    private static final int MAX_RECENT_KEYS = 20;
    private static final Set<String> EVENT_TYPES = Set.of(
            "THEORY_PAPER", "LAB_RESULT", "PROTOTYPE_DEMO", "FLIGHT_TEST",
            "OPERATIONAL_DEPLOYMENT", "COMMERCIALIZATION", "INSTITUTIONAL_ADVANCE",
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY", "RETROSPECTIVE", "ROLLBACK");
    private static final Set<String> PUBLICATION_PATHS = Set.of("PRIMARY", "THIRD_PARTY", "WIRE_REPRINT");

    private final AnthropicClient client;
    private final CostGuard costGuard;
    private final TrackerRepository repository;
    private final String model;
    private final List<Map<String, Object>> systemBlocks;
    private final Map<String, Object> toolSchema;

    public DeepClassifier(
            AnthropicClient client,
            CostGuard costGuard,
            TrackerRepository repository,
            @Value("${tracker.classify-model:claude-opus-4-8}") String model) {
        this.client = client;
        this.costGuard = costGuard;
        this.repository = repository;
        this.model = model;
        this.systemBlocks = List.of(Map.of(
                "type", "text",
                "text", loadResource("tracker/prompt-classify-system.txt"),
                "cache_control", Map.of("type", "ephemeral")));
        this.toolSchema = parseSchema(loadResource("tracker/classify-tool-schema.json"));
    }

    public List<ClaimDraft> classify(ArticleRow article, List<String> recentNaturalKeys) {
        Map<String, Object> output = client.completeWithTool(
                model, systemBlocks, userMessage(article, recentNaturalKeys), toolSchema, TOOL_NAME);
        List<ClaimDraft> drafts = new ArrayList<>();
        if (output.get("relevant_claims") instanceof List<?> claims) {
            for (Object rawClaim : claims) {
                if (rawClaim instanceof Map<?, ?> claim) {
                    ClaimDraft draft = parseClaim(claim, article.id());
                    if (draft != null) {
                        drafts.add(draft);
                    }
                }
            }
        }
        return drafts;
    }

    public void process(ArticleRow article) {
        if (!costGuard.allow()) {
            return;
        }
        try {
            List<ClaimDraft> drafts = classify(article, repository.findRecentNaturalKeys(MAX_RECENT_KEYS));
            Long rubricVersionId = null;
            for (ClaimDraft draft : drafts) {
                if (!repository.nodeCodeExists(draft.nodeCode())) {
                    log.warn("tracker claim rejected for article {}: unregistered node {}",
                            article.id(), draft.nodeCode());
                    continue;
                }
                if (rubricVersionId == null) {
                    rubricVersionId = repository.activeRubricVersionId();
                }
                boolean quoteVerified = quoteAppearsInBody(draft.evidenceQuote(), article.body());
                if (!quoteVerified) {
                    log.warn("tracker claim quote unverified for article {} node {}",
                            article.id(), draft.nodeCode());
                }
                repository.insertClassification(new ClassificationRow(
                        0, article.id(), null, draft.nodeCode(), draft.eventType(),
                        draft.claimedLevel(), draft.actor(), draft.occurredOn(),
                        draft.publicationPath(), draft.evidenceQuote(), quoteVerified,
                        toJson(draft), rubricVersionId));
            }
            repository.updateArticleStatus(article.id(), "CLASSIFIED");
        } catch (CostLimitExceededException limitReachedBetweenChecks) {
            // Deliberately leave GATE_PASSED for the next UTC-day run.
        }
    }

    @Scheduled(cron = "${tracker.classify-cron:0 40 * * * *}")
    @SchedulerLock(name = "tracker-deep-classifier", lockAtLeastFor = "PT1M")
    public void runOnce() {
        for (ArticleRow article : repository.findByStatus("GATE_PASSED", 100)) {
            try {
                process(article);
            } catch (RuntimeException e) {
                log.warn("deep classification failed for article {}: {}", article.id(), e.toString());
            }
        }
    }

    private static ClaimDraft parseClaim(Map<?, ?> claim, long articleId) {
        String nodeCode = str(claim.get("node_code"));
        String eventType = str(claim.get("event_type"));
        String publicationPath = str(claim.get("publication_path"));
        String evidenceQuote = str(claim.get("evidence_quote"));
        String occurredOnText = str(claim.get("occurred_on"));
        Object rawLevel = claim.get("claimed_level");

        if (nodeCode == null || evidenceQuote == null) {
            return reject(articleId, "missing node_code or evidence_quote");
        }
        if (eventType == null || !EVENT_TYPES.contains(eventType)) {
            return reject(articleId, "unknown event_type " + eventType);
        }
        Integer claimedLevel = null;
        if (rawLevel != null) {
            if (!(rawLevel instanceof Number number)) {
                return reject(articleId, "non-numeric claimed_level " + rawLevel);
            }
            claimedLevel = number.intValue();
            if (claimedLevel < 1 || claimedLevel > 9) {
                return reject(articleId, "claimed_level out of range " + claimedLevel);
            }
        }
        if (publicationPath == null || !PUBLICATION_PATHS.contains(publicationPath)) {
            return reject(articleId, "unknown publication_path " + publicationPath);
        }
        LocalDate occurredOn;
        try {
            occurredOn = occurredOnText == null ? null : LocalDate.parse(occurredOnText);
        } catch (DateTimeParseException e) {
            return reject(articleId, "unparseable occurred_on " + occurredOnText);
        }
        if (occurredOn == null) {
            return reject(articleId, "missing occurred_on");
        }
        String duplicateHint = str(claim.get("duplicate_hint"));
        return new ClaimDraft(nodeCode, eventType, claimedLevel, str(claim.get("actor")),
                occurredOn, publicationPath, evidenceQuote, duplicateHint == null ? "NEW" : duplicateHint);
    }

    private static ClaimDraft reject(long articleId, String reason) {
        log.warn("tracker claim rejected for article {}: {}", articleId, reason);
        return null;
    }

    private static boolean quoteAppearsInBody(String quote, String body) {
        if (body == null) {
            return false;
        }
        return normalize(body).contains(normalize(quote));
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String userMessage(ArticleRow article, List<String> recentNaturalKeys) {
        String keys = recentNaturalKeys.isEmpty() ? "NONE" : String.join("\n", recentNaturalKeys);
        return "Title: " + nullToEmpty(article.title())
                + "\n\nRecent event natural keys (for duplicate_hint):\n" + keys
                + "\n\nArticle body:\n" + nullToEmpty(article.body());
    }

    private static String toJson(ClaimDraft draft) {
        var wire = new java.util.LinkedHashMap<String, Object>();
        wire.put("node_code", draft.nodeCode());
        wire.put("event_type", draft.eventType());
        wire.put("claimed_level", draft.claimedLevel());
        wire.put("actor", draft.actor());
        wire.put("occurred_on", draft.occurredOn() == null ? null : draft.occurredOn().toString());
        wire.put("publication_path", draft.publicationPath());
        wire.put("evidence_quote", draft.evidenceQuote());
        wire.put("duplicate_hint", draft.duplicateHint());
        try {
            return JSON.writeValueAsString(wire);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize tracker claim", e);
        }
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse tracker classify tool schema", e);
        }
    }

    private static String loadResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load tracker resource " + path, e);
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
