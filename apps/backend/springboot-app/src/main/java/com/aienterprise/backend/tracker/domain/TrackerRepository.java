package com.aienterprise.backend.tracker.domain;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.event.SourceEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TrackerRepository {

    private static final int MAX_DEADMAN_SOURCES = 16;
    private static final int MAX_DEADMAN_TIMESTAMPS_PER_SOURCE = 64;
    private static final ObjectMapper OPS_JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public TrackerRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> insertArticleIfNew(
            String url,
            String urlHash,
            long sourceId,
            String title,
            Instant publishedAt,
            String body) {
        // Legacy shape: no extraction attempted, matching the V3 backfill.
        return insertArticleIfNew(url, urlHash, sourceId, title, publishedAt, body, "SKIPPED");
    }

    public Optional<Long> insertArticleIfNew(
            String url,
            String urlHash,
            long sourceId,
            String title,
            Instant publishedAt,
            String body,
            String bodyExtractionStatus) {
        if (articleIdByHash(urlHash).isPresent()) {
            return Optional.empty();
        }
        try {
            jdbc.sql("""
                    INSERT INTO article
                      (source_id, url, url_hash, title, published_at, body, body_extracted,
                       pipeline_status, body_extraction_status)
                    VALUES
                      (:sourceId, :url, :urlHash, :title, :publishedAt, :body, 'N',
                       'INGESTED', :bodyExtractionStatus)
                    """)
                    .param("sourceId", sourceId)
                    .param("url", url)
                    .param("urlHash", urlHash)
                    .param("title", title)
                    .param("publishedAt", publishedAt == null ? null : Timestamp.from(publishedAt))
                    .param("body", body)
                    .param("bodyExtractionStatus", bodyExtractionStatus)
                    .update();
            return articleIdByHash(urlHash);
        } catch (DuplicateKeyException concurrentDuplicate) {
            return Optional.empty();
        }
    }

    public List<ArticleRow> findByStatus(String status, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 1_000);
        // INGESTED rows stay invisible to the gate until extraction reaches a
        // terminal state; other pipeline statuses are unaffected.
        String pendingFilter = "INGESTED".equals(status)
                ? " AND body_extraction_status <> 'PENDING'"
                : "";
        return jdbc.sql("""
                SELECT id, source_id, url, url_hash, title, published_at, fetched_at,
                       body, body_extracted, body_extraction_status, pipeline_status, fail_count
                  FROM article
                 WHERE pipeline_status = :status%s
                 ORDER BY fetched_at, id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(pendingFilter, safeLimit))
                .param("status", status)
                .query(TrackerRepository::mapArticle)
                .list();
    }

    public List<ExtractionCandidate> findPendingExtractions(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 100);
        record PendingRow(long id, long sourceId, String url) {
        }
        List<PendingRow> rows = jdbc.sql("""
                SELECT id, source_id, url
                  FROM article
                 WHERE body_extraction_status = 'PENDING'
                 ORDER BY fetched_at, id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query((rs, rowNum) -> new PendingRow(
                        rs.getLong("id"), rs.getLong("source_id"), rs.getString("url")))
                .list();
        java.util.Map<Long, Set<String>> hostsBySource = new java.util.HashMap<>();
        return rows.stream()
                .map(row -> new ExtractionCandidate(row.id(), row.url(),
                        hostsBySource.computeIfAbsent(row.sourceId(),
                                sourceId -> findActiveDomains(sourceId, "BODY"))))
                .toList();
    }

    public void completeExtraction(long id, String text) {
        int changed = jdbc.sql("""
                UPDATE article
                   SET body = :text,
                       body_extracted = 'Y',
                       body_extraction_status = 'EXTRACTED',
                       body_extraction_error = NULL
                 WHERE id = :id
                """)
                .param("text", text)
                .param("id", id)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown article id: " + id);
        }
    }

    public String recordExtractionFailure(long id, String message, int maxAttempts) {
        int attempts = jdbc.sql("SELECT body_extraction_attempts FROM article WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown article id: " + id));
        int nextAttempts = attempts + 1;
        String status = nextAttempts >= maxAttempts ? "FAILED" : "PENDING";
        jdbc.sql("""
                UPDATE article
                   SET body_extraction_attempts = :attempts,
                       body_extraction_status = :status,
                       body_extraction_error = :error
                 WHERE id = :id
                """)
                .param("attempts", nextAttempts)
                .param("status", status)
                .param("error", boundedError(message))
                .param("id", id)
                .update();
        return status;
    }

    public void skipExtraction(long id, String message) {
        int changed = jdbc.sql("""
                UPDATE article
                   SET body_extraction_status = 'SKIPPED',
                       body_extraction_error = :error
                 WHERE id = :id
                """)
                .param("error", boundedError(message))
                .param("id", id)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown article id: " + id);
        }
    }

    private static String boundedError(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.length() > 1_000 ? message.substring(0, 1_000) : message;
    }

    public void updateArticleStatus(long id, String status) {
        int changed = jdbc.sql("UPDATE article SET pipeline_status = :status WHERE id = :id")
                .param("status", status)
                .param("id", id)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown article id: " + id);
        }
    }

    public Optional<Long> findSourceIdForFeed(String sourceCode, String host) {
        List<Long> ids = jdbc.sql("""
                SELECT id
                  FROM source_registry
                 WHERE UPPER(code) = UPPER(:sourceCode)
                    OR LOWER(site_domain) = LOWER(:host)
                 ORDER BY CASE WHEN UPPER(code) = UPPER(:sourceCode) THEN 0 ELSE 1 END
                 FETCH FIRST 1 ROWS ONLY
                """)
                .param("sourceCode", sourceCode)
                .param("host", host)
                .query(Long.class)
                .list();
        return ids.stream().findFirst();
    }

    public List<SourceDomainRow> findActiveSourceDomains() {
        return jdbc.sql("""
                SELECT s.id AS source_id, s.code AS source_code,
                       LOWER(d.domain) AS domain, d.purpose
                  FROM source_domain d
                  JOIN source_registry s ON s.id = d.source_id
                 WHERE d.active = 'Y'
                 ORDER BY s.code, d.domain
                """)
                .query((rs, rowNum) -> new SourceDomainRow(
                        rs.getLong("source_id"),
                        rs.getString("source_code"),
                        rs.getString("domain"),
                        rs.getString("purpose")))
                .list();
    }

    public Set<String> findActiveDomains(long sourceId, String purpose) {
        String normalizedPurpose = normalizeDomainPurpose(purpose);
        return Set.copyOf(jdbc.sql("""
                SELECT LOWER(domain)
                  FROM source_domain
                 WHERE source_id = :sourceId
                   AND active = 'Y'
                   AND purpose IN (:purpose, 'BOTH')
                 ORDER BY domain
                """)
                .param("sourceId", sourceId)
                .param("purpose", normalizedPurpose)
                .query(String.class)
                .list());
    }

    public boolean isRegisteredFeed(String sourceCode, String host) {
        if (sourceCode == null || sourceCode.isBlank() || host == null || host.isBlank()) {
            return false;
        }
        return jdbc.sql("""
                SELECT COUNT(*)
                  FROM source_registry s
                  JOIN source_domain d ON d.source_id = s.id
                 WHERE UPPER(s.code) = UPPER(:sourceCode)
                   AND s.feed_active = 'Y'
                   AND d.active = 'Y'
                   AND d.purpose IN ('FEED', 'BOTH')
                   AND LOWER(d.domain) = LOWER(:host)
                """)
                .param("sourceCode", sourceCode.trim())
                .param("host", host.trim())
                .query(Integer.class)
                .single() == 1;
    }

    private static String normalizeDomainPurpose(String purpose) {
        if (purpose == null) {
            throw new IllegalArgumentException("Domain purpose is required");
        }
        String normalized = purpose.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("FEED", "BODY", "BOTH").contains(normalized)) {
            throw new IllegalArgumentException("Unknown domain purpose: " + purpose);
        }
        return normalized;
    }

    public long upsertEventByNaturalKey(String naturalKey, EventRow draft) {
        Optional<Long> existing = eventIdByNaturalKey(naturalKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbc.sql("""
                    INSERT INTO event
                      (natural_key, node_id, event_type, claimed_level, actor, occurred_on,
                       verification_level, event_status, provisional_expires_on,
                       impact_score, novelty, state_advanced, rubric_version_id)
                    VALUES
                      (:naturalKey, :nodeId, :eventType, :claimedLevel, :actor, :occurredOn,
                       :verification, :eventStatus, :expiresOn,
                       :impactScore, :novelty, :stateAdvanced, :rubricVersionId)
                    """)
                    .param("naturalKey", naturalKey)
                    .param("nodeId", draft.nodeId())
                    .param("eventType", draft.eventType())
                    .param("claimedLevel", draft.claimedLevel())
                    .param("actor", draft.actor())
                    .param("occurredOn", date(draft.occurredOn()))
                    .param("verification", draft.verificationLevel())
                    .param("eventStatus", draft.eventStatus())
                    .param("expiresOn", date(draft.provisionalExpiresOn()))
                    .param("impactScore", draft.impactScore())
                    .param("novelty", draft.novelty())
                    .param("stateAdvanced", draft.stateAdvanced() ? "Y" : "N")
                    .param("rubricVersionId", draft.rubricVersionId())
                    .update();
        } catch (DuplicateKeyException concurrentDuplicate) {
            // Another scheduler instance won the unique-key race.
        }
        return eventIdByNaturalKey(naturalKey)
                .orElseThrow(() -> new IllegalStateException("Event upsert produced no row: " + naturalKey));
    }

    public Optional<Long> findEventIdByNaturalKey(String naturalKey) {
        return eventIdByNaturalKey(naturalKey);
    }

    /**
     * Bounded candidate events for semantic merge: same node and event type,
     * occurred-on within {@code intervalDays}, excluding the exact natural key,
     * capped at {@code limit} (never more than 50). Each row carries one verified
     * evidence quote for embedding.
     */
    public List<MergeCandidate> findMergeCandidates(
            long nodeId, String eventType, LocalDate occurredOn,
            int intervalDays, String excludeNaturalKey, int limit) {
        int safeLimit = Math.max(0, Math.min(limit, 50));
        if (safeLimit == 0) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT e.id, e.actor, e.occurred_on,
                       (SELECT c.evidence_quote FROM article_classification c
                         WHERE c.event_id = e.id AND c.quote_verified = 'Y'
                           AND c.evidence_quote IS NOT NULL
                         ORDER BY c.id FETCH FIRST 1 ROW ONLY) AS quote
                  FROM event e
                 WHERE e.node_id = :nodeId
                   AND e.event_type = :eventType
                   AND e.occurred_on BETWEEN :fromDate AND :toDate
                   AND e.natural_key <> :excludeKey
                 ORDER BY e.id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .param("nodeId", nodeId)
                .param("eventType", eventType)
                .param("fromDate", date(occurredOn.minusDays(intervalDays)))
                .param("toDate", date(occurredOn.plusDays(intervalDays)))
                .param("excludeKey", excludeNaturalKey)
                .query((rs, rowNum) -> new MergeCandidate(
                        rs.getLong("id"),
                        rs.getString("actor"),
                        localDate(rs.getDate("occurred_on")),
                        rs.getString("quote")))
                .list();
    }

    public long upsertLayerBMetric(LayerBMetric draft) {
        Optional<Long> existing = jdbc.sql(
                "SELECT id FROM layer_b_metric WHERE metric_code = :code AND observed_on = :on")
                .param("code", draft.metricCode()).param("on", date(draft.observedOn()))
                .query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.sql("""
                INSERT INTO layer_b_metric
                  (metric_code, pillar, observed_on, metric_value, unit, basis,
                   source_label, source_url, accessed_on, content_sha256, fact_summary)
                VALUES
                  (:code, :pillar, :on, :value, :unit, :basis,
                   :label, :url, :accessed, :hash, :summary)
                """)
                .param("code", draft.metricCode()).param("pillar", draft.pillar())
                .param("on", date(draft.observedOn())).param("value", draft.value())
                .param("unit", draft.unit()).param("basis", draft.basis())
                .param("label", draft.sourceLabel()).param("url", draft.sourceUrl())
                .param("accessed", date(draft.accessedOn())).param("hash", draft.contentSha256())
                .param("summary", draft.factSummary()).update();
        return jdbc.sql("SELECT id FROM layer_b_metric WHERE metric_code = :code AND observed_on = :on")
                .param("code", draft.metricCode()).param("on", date(draft.observedOn()))
                .query(Long.class).single();
    }

    public int countLayerBMetrics() {
        return jdbc.sql("SELECT COUNT(*) FROM layer_b_metric").query(Integer.class).single();
    }

    public List<LayerBMetric> findLatestLayerBByPillar() {
        return jdbc.sql("""
                SELECT m.id, m.metric_code, m.pillar, m.observed_on, m.metric_value, m.unit, m.basis,
                       m.source_label, m.source_url, m.accessed_on, m.content_sha256, m.fact_summary
                  FROM layer_b_metric m
                 WHERE m.observed_on = (SELECT MAX(m2.observed_on) FROM layer_b_metric m2
                                         WHERE m2.metric_code = m.metric_code)
                 ORDER BY m.pillar, m.metric_code
                """)
                .query((rs, rowNum) -> new LayerBMetric(
                        rs.getLong("id"), rs.getString("metric_code"), rs.getInt("pillar"),
                        localDate(rs.getDate("observed_on")), rs.getBigDecimal("metric_value"),
                        rs.getString("unit"), rs.getString("basis"), rs.getString("source_label"),
                        rs.getString("source_url"), localDate(rs.getDate("accessed_on")),
                        rs.getString("content_sha256"), rs.getString("fact_summary")))
                .list();
    }

    public Optional<String> findLayerBImportSha(String datasetVersion) {
        return jdbc.sql("SELECT dataset_sha256 FROM layer_b_metric_import WHERE dataset_version = :v")
                .param("v", datasetVersion).query(String.class).optional();
    }

    public void recordLayerBImport(String datasetVersion, String datasetSha256, int recordCount) {
        jdbc.sql("""
                INSERT INTO layer_b_metric_import (dataset_version, dataset_sha256, record_count)
                VALUES (:v, :h, :n)
                """)
                .param("v", datasetVersion).param("h", datasetSha256).param("n", recordCount)
                .update();
    }

    public long insertClassification(ClassificationRow draft) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO article_classification
                  (article_id, event_id, node_code, event_type, claimed_level, actor,
                   occurred_on, publication_path, evidence_quote, quote_verified,
                   raw_output, rubric_version_id)
                VALUES
                  (:articleId, :eventId, :nodeCode, :eventType, :claimedLevel, :actor,
                   :occurredOn, :publicationPath, :evidenceQuote, :quoteVerified,
                   :rawOutput, :rubricVersionId)
                """)
                .param("articleId", draft.articleId())
                .param("eventId", draft.eventId())
                .param("nodeCode", draft.nodeCode())
                .param("eventType", draft.eventType())
                .param("claimedLevel", draft.claimedLevel())
                .param("actor", draft.actor())
                .param("occurredOn", date(draft.occurredOn()))
                .param("publicationPath", draft.publicationPath())
                .param("evidenceQuote", draft.evidenceQuote())
                .param("quoteVerified", draft.quoteVerified() ? "Y" : "N")
                .param("rawOutput", draft.rawOutput())
                .param("rubricVersionId", draft.rubricVersionId())
                .update(keys, "id");
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Classification insert produced no generated key");
        }
        return key.longValue();
    }

    public List<ClassificationRow> findUnmergedClassifications(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 1_000);
        return jdbc.sql("""
                SELECT id, article_id, event_id, node_code, event_type, claimed_level, actor,
                       occurred_on, publication_path, evidence_quote, quote_verified,
                       raw_output, rubric_version_id
                  FROM article_classification
                 WHERE quote_verified = 'Y'
                   AND event_id IS NULL
                 ORDER BY id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query(TrackerRepository::mapClassification)
                .list();
    }

    public void updateEventVerification(long eventId, String verificationLevel) {
        int changed = jdbc.sql("""
                UPDATE event
                   SET verification_level = :level,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :id
                """)
                .param("level", verificationLevel)
                .param("id", eventId)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown event id: " + eventId);
        }
    }

    public int expireProvisionalEvents(LocalDate today) {
        return jdbc.sql("""
                UPDATE event
                   SET event_status = 'EXPIRED',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE event_status = 'PROVISIONAL'
                   AND provisional_expires_on < :today
                """)
                .param("today", date(today))
                .update();
    }

    public long activeRubricVersionId() {
        return jdbc.sql("SELECT id FROM rubric_version WHERE active = 'Y'")
                .query(Long.class)
                .single();
    }

    public long rubricVersionIdByLabel(String versionLabel) {
        return jdbc.sql("SELECT id FROM rubric_version WHERE version_label = :versionLabel")
                .param("versionLabel", versionLabel)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown rubric version: " + versionLabel));
    }

    public boolean nodeCodeExists(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM capability_node WHERE code = :code")
                .param("code", code)
                .query(Integer.class)
                .single() > 0;
    }

    public List<String> findRecentNaturalKeys(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 100);
        return jdbc.sql("""
                SELECT natural_key
                  FROM event
                 ORDER BY created_at DESC, id DESC
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query(String.class)
                .list();
    }

    public void linkClassification(long classificationId, long eventId) {
        int changed = jdbc.sql("UPDATE article_classification SET event_id = :eventId WHERE id = :id")
                .param("eventId", eventId)
                .param("id", classificationId)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown classification id: " + classificationId);
        }
    }

    public List<SourceEvidence> findClusterEvidence(long eventId) {
        return jdbc.sql("""
                SELECT DISTINCT s.id, s.tier, s.source_type, c.publication_path
                  FROM article_classification c
                  JOIN article a ON a.id = c.article_id
                  JOIN source_registry s ON s.id = a.source_id
                 WHERE c.event_id = :eventId
                   AND c.quote_verified = 'Y'
                """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new SourceEvidence(
                        rs.getLong("id"),
                        rs.getInt("tier"),
                        rs.getString("source_type"),
                        rs.getString("publication_path")))
                .list();
    }

    public EventRow findEventById(long id) {
        return jdbc.sql(EVENT_SELECT + " WHERE id = :id")
                .param("id", id)
                .query(TrackerRepository::mapEvent)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown event id: " + id));
    }

    public List<EventRow> findEventsForScoring(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 1_000);
        return jdbc.sql(EVENT_SELECT + """

                 WHERE event_status = 'PROVISIONAL'
                   AND state_advanced = 'N'
                   AND verification_level IN ('PEER_REVIEWED', 'OFFICIAL', 'INDEPENDENT')
                   AND NOT EXISTS (SELECT 1 FROM review_queue r
                                    WHERE r.event_id = event.id AND r.status = 'PENDING')
                 ORDER BY id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query(TrackerRepository::mapEvent)
                .list();
    }

    public void recordEventScore(long eventId, double impactScore, int novelty) {
        int changed = jdbc.sql("""
                UPDATE event
                   SET impact_score = :impact, novelty = :novelty, updated_at = CURRENT_TIMESTAMP
                 WHERE id = :id
                """)
                .param("impact", impactScore)
                .param("novelty", novelty)
                .param("id", eventId)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown event id: " + eventId);
        }
    }

    public void markEventConfirmed(long eventId) {
        markEventConfirmed(eventId, true);
    }

    public void markEventConfirmed(long eventId, boolean stateAdvanced) {
        setEventStatus(eventId, "CONFIRMED", stateAdvanced ? "Y" : "N");
    }

    public void markEventRejected(long eventId) {
        setEventStatus(eventId, "REJECTED", "N");
    }

    public long insertReview(long eventId, String reason) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO review_queue (event_id, reason) VALUES (:eventId, :reason)")
                .param("eventId", eventId)
                .param("reason", reason)
                .update(keys, "id");
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Review insert produced no generated key");
        }
        return key.longValue();
    }

    public long insertReviewIfAbsent(long eventId, String reason) {
        Optional<Long> existing = reviewIdByEventAndReason(eventId, reason);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return insertReview(eventId, reason);
        } catch (DuplicateKeyException concurrentDuplicate) {
            return reviewIdByEventAndReason(eventId, reason)
                    .orElseThrow(() -> new IllegalStateException(
                            "Review upsert produced no row for event " + eventId));
        }
    }

    public List<ReviewRow> findReviewsForFluke(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 100);
        return jdbc.sql(REVIEW_SELECT + """

                 WHERE status = 'PENDING'
                   AND fluke_status = 'PENDING'
                 ORDER BY created_at, id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query(TrackerRepository::mapReview)
                .list();
    }

    @Transactional
    public void storeFlukeEvaluation(
            long reviewId,
            long eventId,
            String verdict,
            String evidenceQuote,
            boolean quoteVerified,
            String rawOutput,
            String modelId,
            String promptSha256,
            long rubricVersionId,
            int priority) {
        jdbc.sql("""
                INSERT INTO fluke_evaluation
                  (review_id, event_id, verdict, evidence_quote, quote_verified,
                   raw_output, model_id, prompt_sha256, rubric_version_id)
                VALUES
                  (:reviewId, :eventId, :verdict, :evidenceQuote, :quoteVerified,
                   :rawOutput, :modelId, :promptSha256, :rubricVersionId)
                """)
                .param("reviewId", reviewId)
                .param("eventId", eventId)
                .param("verdict", verdict)
                .param("evidenceQuote", evidenceQuote)
                .param("quoteVerified", quoteVerified ? "Y" : "N")
                .param("rawOutput", rawOutput)
                .param("modelId", modelId)
                .param("promptSha256", promptSha256)
                .param("rubricVersionId", rubricVersionId)
                .update();
        int changed = jdbc.sql("""
                UPDATE review_queue
                   SET fluke_status = 'COMPLETE',
                       fluke_result = :verdict,
                       priority = :priority,
                       fluke_last_error = NULL
                 WHERE id = :id
                """)
                .param("verdict", verdict)
                .param("priority", priority)
                .param("id", reviewId)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown review id: " + reviewId);
        }
    }

    public String recordFlukeFailure(long reviewId, String message, int maxAttempts) {
        int failures = jdbc.sql("SELECT fluke_fail_count FROM review_queue WHERE id = :id")
                .param("id", reviewId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown review id: " + reviewId));
        int nextFailures = failures + 1;
        boolean terminal = nextFailures >= maxAttempts;
        jdbc.sql("""
                UPDATE review_queue
                   SET fluke_fail_count = :failures,
                       fluke_status = :status,
                       priority = :priority,
                       fluke_last_error = :error
                 WHERE id = :id
                """)
                .param("failures", nextFailures)
                .param("status", terminal ? "FAILED" : "PENDING")
                .param("priority", terminal ? 2 : 0)
                .param("error", boundedError(message))
                .param("id", reviewId)
                .update();
        return terminal ? "FAILED" : "PENDING";
    }

    /**
     * Formal review history page. Status and reason are enum allowlists; only
     * validated numeric bounds are formatted into the Oracle pagination
     * clause. Evidence is fetched once for the current page, never per row.
     */
    public ReviewPage findReviewPage(
            ReviewPage.Status status,
            ReviewPage.Reason reason,
            int page,
            int size) {
        if (status == null || page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid review page bounds");
        }
        String reasonValue = reason == null ? null : reason.name();
        long total = jdbc.sql("""
                SELECT COUNT(*)
                  FROM review_queue r
                 WHERE r.status = :status
                   AND (:reason IS NULL OR r.reason = :reason)
                """)
                .param("status", status.name())
                .param("reason", reasonValue, Types.VARCHAR)
                .query(Long.class)
                .single();
        long offset = (long) page * size;
        List<ReviewCase> skeletons = jdbc.sql("""
                SELECT r.id AS review_id, r.reason, r.priority, r.fluke_status, r.fluke_result,
                       r.created_at, r.status, r.reviewer_note, r.resolved_at,
                       e.id AS event_id, e.event_type, e.occurred_on, e.actor,
                       e.verification_level, e.impact_score, e.claimed_level,
                       n.code AS node_code, n.name_ko, n.scale_type, n.current_level
                  FROM review_queue r
                  JOIN event e ON e.id = r.event_id
                  JOIN capability_node n ON n.id = e.node_id
                 WHERE r.status = :status
                   AND (:reason IS NULL OR r.reason = :reason)
                 ORDER BY r.priority DESC, r.created_at, r.id
                 OFFSET %d ROWS FETCH NEXT %d ROWS ONLY
                """.formatted(offset, size))
                .param("status", status.name())
                .param("reason", reasonValue, Types.VARCHAR)
                .query(TrackerRepository::mapReviewCaseSkeleton)
                .list();
        List<ReviewCase> items = attachReviewEvidence(skeletons);
        long totalPages = total == 0 ? 0 : ((total - 1) / size) + 1;
        return new ReviewPage(items, page, size, total, totalPages, ReviewPage.STABLE_SORT);
    }

    /** Compatibility adapter for the original pending-only admin endpoint. */
    public List<ReviewCase> findPendingReviewCases(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return findReviewPage(ReviewPage.Status.PENDING, null, 0, Math.min(limit, 100)).items();
    }

    private List<ReviewCase> attachReviewEvidence(List<ReviewCase> skeletons) {
        if (skeletons.isEmpty()) {
            return skeletons;
        }

        record EvidenceRow(long eventId, long sourceId, ReviewEvidence evidence) {
        }
        List<Long> eventIds = skeletons.stream().map(ReviewCase::eventId).distinct().toList();
        List<EvidenceRow> evidenceRows = new java.util.ArrayList<>(jdbc.sql("""
                SELECT c.event_id, a.source_id,
                       COALESCE(a.title, s.name) AS source_label,
                       a.url, c.evidence_quote
                  FROM article_classification c
                  JOIN article a ON a.id = c.article_id
                  JOIN source_registry s ON s.id = a.source_id
                 WHERE c.quote_verified = 'Y'
                   AND c.event_id IN (:eventIds)
                 ORDER BY c.id
                """)
                .param("eventIds", eventIds)
                .query((rs, rowNum) -> new EvidenceRow(
                        rs.getLong("event_id"),
                        rs.getLong("source_id"),
                        new ReviewEvidence(
                                EvidenceKind.VERBATIM,
                                rs.getString("source_label"),
                                rs.getString("url"),
                                rs.getString("evidence_quote"),
                                null,
                                null,
                                null)))
                .list());
        evidenceRows.addAll(jdbc.sql("""
                SELECT h.event_id, h.source_id, s.name, h.url,
                       h.fact_summary, h.locator, h.accessed_on
                  FROM historical_evidence h
                  JOIN source_registry s ON s.id = h.source_id
                 WHERE h.event_id IN (:eventIds)
                   AND h.fact_review_status = 'APPROVED'
                   AND h.rubric_review_status = 'APPROVED'
                   AND h.reference_status = 'APPROVED'
                 ORDER BY h.id
                """)
                .param("eventIds", eventIds)
                .query((rs, rowNum) -> new EvidenceRow(
                        rs.getLong("event_id"),
                        rs.getLong("source_id"),
                        new ReviewEvidence(
                                EvidenceKind.HISTORICAL_REFERENCE,
                                rs.getString("name"),
                                rs.getString("url"),
                                null,
                                rs.getString("fact_summary"),
                                rs.getString("locator"),
                                localDate(rs.getDate("accessed_on")))))
                .list());

        java.util.Map<Long, List<ReviewEvidence>> evidenceByEvent = new java.util.HashMap<>();
        java.util.Map<Long, Set<Long>> sourcesByEvent = new java.util.HashMap<>();
        for (EvidenceRow row : evidenceRows) {
            evidenceByEvent.computeIfAbsent(row.eventId(), key -> new java.util.ArrayList<>())
                    .add(row.evidence());
            sourcesByEvent.computeIfAbsent(row.eventId(), key -> new java.util.HashSet<>())
                    .add(row.sourceId());
        }
        return skeletons.stream()
                .map(skeleton -> new ReviewCase(
                        skeleton.reviewId(), skeleton.reason(), skeleton.priority(),
                        skeleton.flukeStatus(), skeleton.flukeResult(), skeleton.createdAt(),
                        skeleton.eventId(), skeleton.eventType(), skeleton.occurredOn(),
                        skeleton.actor(), skeleton.verificationLevel(), skeleton.impactScore(),
                        skeleton.claimedLevel(), skeleton.nodeCode(), skeleton.nodeName(),
                        skeleton.scaleType(), skeleton.currentLevel(),
                        sourcesByEvent.getOrDefault(skeleton.eventId(), Set.of()).size(),
                        List.copyOf(evidenceByEvent.getOrDefault(skeleton.eventId(), List.of())),
                        skeleton.status(), skeleton.reviewerNote(), skeleton.resolvedAt()))
                .toList();
    }

    private static ReviewCase mapReviewCaseSkeleton(ResultSet rs, int rowNum) throws SQLException {
        return new ReviewCase(
                rs.getLong("review_id"),
                rs.getString("reason"),
                rs.getInt("priority"),
                rs.getString("fluke_status"),
                rs.getString("fluke_result"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("event_id"),
                rs.getString("event_type"),
                localDate(rs.getDate("occurred_on")),
                rs.getString("actor"),
                rs.getString("verification_level"),
                nullableDouble(rs, "impact_score"),
                nullableInt(rs, "claimed_level"),
                rs.getString("node_code"),
                rs.getString("name_ko"),
                rs.getString("scale_type"),
                rs.getInt("current_level"),
                0,
                List.of(),
                rs.getString("status"),
                rs.getString("reviewer_note"),
                nullableInstant(rs.getTimestamp("resolved_at")));
    }

    private Optional<Long> reviewIdByEventAndReason(long eventId, String reason) {
        return jdbc.sql("SELECT id FROM review_queue WHERE event_id = :eventId AND reason = :reason")
                .param("eventId", eventId)
                .param("reason", reason)
                .query(Long.class)
                .optional();
    }

    public Optional<ReviewRow> findReviewById(long id) {
        return jdbc.sql(REVIEW_SELECT + " WHERE id = :id")
                .param("id", id)
                .query(TrackerRepository::mapReview)
                .optional();
    }

    public List<ReviewRow> findPendingReviews(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 1_000);
        return jdbc.sql(REVIEW_SELECT + """

                 WHERE status = 'PENDING'
                 ORDER BY created_at, id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query(TrackerRepository::mapReview)
                .list();
    }

    public boolean resolveReview(long reviewId, String status, String note) {
        return jdbc.sql("""
                UPDATE review_queue
                   SET status = :status, reviewer_note = :note, resolved_at = CURRENT_TIMESTAMP
                 WHERE id = :id
                   AND status = 'PENDING'
                """)
                .param("status", status)
                .param("note", note)
                .param("id", reviewId)
                .update() == 1;
    }

    public boolean reopenApprovedReview(long reviewId) {
        return jdbc.sql("""
                UPDATE review_queue
                   SET status = 'PENDING', reviewer_note = NULL, resolved_at = NULL
                 WHERE id = :id
                   AND status = 'APPROVED'
                """)
                .param("id", reviewId)
                .update() == 1;
    }

    public int resolvePendingCircuitBreakerReviews(String note) {
        return jdbc.sql("""
                UPDATE review_queue
                   SET status = 'APPROVED', reviewer_note = :note,
                       resolved_at = CURRENT_TIMESTAMP
                 WHERE reason = 'CIRCUIT_BREAKER'
                   AND status = 'PENDING'
                """)
                .param("note", note)
                .update();
    }

    public long countEvents() {
        return jdbc.sql("SELECT COUNT(*) FROM event").query(Long.class).single();
    }

    public Set<Long> findNodeIdsWithConfirmedState() {
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT node_id FROM event
                 WHERE event_status = 'CONFIRMED'
                   AND state_advanced = 'Y'
                """).query(Long.class).list());
    }

    public Optional<BackfillImportRow> findBackfillImport(String datasetVersion) {
        return jdbc.sql("""
                SELECT dataset_version, dataset_sha256, node_set_version,
                       rubric_version_id, imported_at, record_count
                  FROM backfill_import
                 WHERE dataset_version = :datasetVersion
                """)
                .param("datasetVersion", datasetVersion)
                .query((rs, rowNum) -> new BackfillImportRow(
                        rs.getString("dataset_version"),
                        rs.getString("dataset_sha256"),
                        rs.getString("node_set_version"),
                        rs.getLong("rubric_version_id"),
                        rs.getTimestamp("imported_at").toInstant(),
                        rs.getInt("record_count")))
                .optional();
    }

    public long sourceIdByCode(String sourceCode) {
        return jdbc.sql("SELECT id FROM source_registry WHERE code = :sourceCode")
                .param("sourceCode", sourceCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source code: " + sourceCode));
    }

    public SourceEvidence sourceEvidenceByCode(String sourceCode, String publicationPath) {
        return jdbc.sql("""
                SELECT id, tier, source_type
                  FROM source_registry
                 WHERE code = :sourceCode
                """)
                .param("sourceCode", sourceCode)
                .query((rs, rowNum) -> new SourceEvidence(
                        rs.getLong("id"),
                        rs.getInt("tier"),
                        rs.getString("source_type"),
                        publicationPath))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source code: " + sourceCode));
    }

    public void insertHistoricalEvidence(HistoricalEvidenceRow row) {
        jdbc.sql("""
                INSERT INTO historical_evidence
                  (backfill_id, candidate_id, occurred_on_precision,
                   event_id, source_id, url, locator, accessed_on,
                   content_sha256, publication_path, fact_summary,
                   fact_review_status, rubric_review_status,
                   reference_status, reviewer_note)
                VALUES
                  (:backfillId, :candidateId, :occurredOnPrecision,
                   :eventId, :sourceId, :url, :locator, :accessedOn,
                   :contentSha256, :publicationPath, :factSummary,
                   :factReviewStatus, :rubricReviewStatus,
                   :referenceStatus, :reviewerNote)
                """)
                .param("backfillId", row.backfillId())
                .param("candidateId", row.candidateId())
                .param("occurredOnPrecision", row.occurredOnPrecision())
                .param("eventId", row.eventId())
                .param("sourceId", row.sourceId())
                .param("url", row.url())
                .param("locator", row.locator())
                .param("accessedOn", date(row.accessedOn()))
                .param("contentSha256", row.contentSha256())
                .param("publicationPath", row.publicationPath())
                .param("factSummary", row.factSummary())
                .param("factReviewStatus", row.factReviewStatus())
                .param("rubricReviewStatus", row.rubricReviewStatus())
                .param("referenceStatus", row.referenceStatus())
                .param("reviewerNote", row.reviewerNote())
                .update();
    }

    public void recordBackfillImport(BackfillImportRow row) {
        jdbc.sql("""
                INSERT INTO backfill_import
                  (dataset_version, dataset_sha256, node_set_version,
                   rubric_version_id, record_count)
                VALUES
                  (:datasetVersion, :datasetSha256, :nodeSetVersion,
                   :rubricVersionId, :recordCount)
                """)
                .param("datasetVersion", row.datasetVersion())
                .param("datasetSha256", row.datasetSha256())
                .param("nodeSetVersion", row.nodeSetVersion())
                .param("rubricVersionId", row.rubricVersionId())
                .param("recordCount", row.recordCount())
                .update();
    }

    public List<NodeRow> findAllNodes() {
        return jdbc.sql(NODE_SELECT + " ORDER BY pillar, code")
                .query(TrackerRepository::mapNode)
                .list();
    }

    public void insertPillarSnapshot(
            int pillar, LocalDate snapshotDate, double readiness, double logitClipped, String paramsVersion) {
        jdbc.sql("""
                INSERT INTO pillar_snapshot (pillar, snapshot_date, readiness, logit_clipped, params_version)
                VALUES (:pillar, :snapshotDate, :readiness, :logitClipped, :paramsVersion)
                """)
                .param("pillar", pillar)
                .param("snapshotDate", date(snapshotDate))
                .param("readiness", readiness)
                .param("logitClipped", logitClipped)
                .param("paramsVersion", paramsVersion)
                .update();
    }

    public void replacePillarSnapshot(
            int pillar, LocalDate snapshotDate, double readiness,
            double logitClipped, String paramsVersion) {
        jdbc.sql("DELETE FROM pillar_snapshot WHERE pillar = :pillar AND snapshot_date = :snapshotDate")
                .param("pillar", pillar)
                .param("snapshotDate", date(snapshotDate))
                .update();
        insertPillarSnapshot(pillar, snapshotDate, readiness, logitClipped, paramsVersion);
    }

    public int deleteBareHistoricalPillarSnapshots(LocalDate from, LocalDate through) {
        return jdbc.sql("""
                DELETE FROM pillar_snapshot
                 WHERE pillar BETWEEN 1 AND 6
                   AND snapshot_date BETWEEN :fromDate AND :throughDate
                   AND trend_fit IS NULL
                   AND trend_used IS NULL
                   AND n_events_window IS NULL
                   AND window_years IS NULL
                   AND eta_year IS NULL
                   AND eta_low IS NULL
                   AND eta_high IS NULL
                   AND displayed_eta_year IS NULL
                """)
                .param("fromDate", date(from))
                .param("throughDate", date(through))
                .update();
    }

    public List<SnapshotRow> findPillarSnapshotsBetween(
            LocalDate from, LocalDate through) {
        return jdbc.sql(SNAPSHOT_SELECT + """
                 WHERE pillar BETWEEN 1 AND 6
                   AND snapshot_date BETWEEN :fromDate AND :throughDate
                 ORDER BY snapshot_date, pillar
                """)
                .param("fromDate", date(from))
                .param("throughDate", date(through))
                .query(TrackerRepository::mapSnapshot)
                .list();
    }

    public void insertBareHistoricalPillarSnapshots(List<SnapshotRow> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO pillar_snapshot
                  (pillar, snapshot_date, readiness, logit_clipped, params_version)
                VALUES (?, ?, ?, ?, ?)
                """, snapshots, 1000, (statement, snapshot) -> {
                    statement.setInt(1, snapshot.pillar());
                    statement.setDate(2, date(snapshot.snapshotDate()));
                    statement.setDouble(3, snapshot.readiness());
                    statement.setDouble(4, snapshot.logitClipped());
                    statement.setString(5, snapshot.paramsVersion());
                });
    }

    public void replaceSnapshot(SnapshotRow snapshot) {
        jdbc.sql("DELETE FROM pillar_snapshot WHERE pillar = :pillar AND snapshot_date = :snapshotDate")
                .param("pillar", snapshot.pillar())
                .param("snapshotDate", date(snapshot.snapshotDate()))
                .update();
        jdbc.sql("""
                INSERT INTO pillar_snapshot
                  (pillar, snapshot_date, readiness, logit_clipped, trend_fit, trend_used,
                   n_events_window, window_years, eta_year, eta_low, eta_high,
                   displayed_eta_year, params_version)
                VALUES
                  (:pillar, :snapshotDate, :readiness, :logitClipped, :trendFit, :trendUsed,
                   :eventsInWindow, :windowYears, :etaYear, :etaLow, :etaHigh,
                   :displayedEtaYear, :paramsVersion)
                """)
                .param("pillar", snapshot.pillar())
                .param("snapshotDate", date(snapshot.snapshotDate()))
                .param("readiness", snapshot.readiness())
                .param("logitClipped", snapshot.logitClipped())
                .param("trendFit", snapshot.trendFit())
                .param("trendUsed", snapshot.trendUsed())
                .param("eventsInWindow", snapshot.eventsInWindow())
                .param("windowYears", snapshot.windowYears())
                .param("etaYear", snapshot.etaYear())
                .param("etaLow", snapshot.etaLow())
                .param("etaHigh", snapshot.etaHigh())
                .param("displayedEtaYear", snapshot.displayedEtaYear())
                .param("paramsVersion", snapshot.paramsVersion())
                .update();
    }

    public List<SnapshotRow> findPillarSnapshots(int pillar) {
        return jdbc.sql(SNAPSHOT_SELECT + " WHERE pillar = :pillar ORDER BY snapshot_date")
                .param("pillar", pillar)
                .query(TrackerRepository::mapSnapshot)
                .list();
    }

    public Optional<SnapshotRow> findLatestSnapshot(int pillar) {
        return jdbc.sql(SNAPSHOT_SELECT + """

                 WHERE pillar = :pillar
                 ORDER BY snapshot_date DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .param("pillar", pillar)
                .query(TrackerRepository::mapSnapshot)
                .optional();
    }

    public Optional<OpsState> findOpsState(String key) {
        return jdbc.sql("SELECT state_value, updated_at FROM ops_state WHERE state_key = :key")
                .param("key", key)
                .query((rs, rowNum) -> new OpsState(
                        rs.getString("state_value"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    public void putOpsState(String key, String value) {
        int changed = jdbc.sql("""
                UPDATE ops_state
                   SET state_value = :value, updated_at = CURRENT_TIMESTAMP
                 WHERE state_key = :key
                """)
                .param("value", value)
                .param("key", key)
                .update();
        if (changed == 0) {
            try {
                jdbc.sql("INSERT INTO ops_state (state_key, state_value) VALUES (:key, :value)")
                        .param("key", key)
                        .param("value", value)
                        .update();
            } catch (DuplicateKeyException concurrentInsert) {
                putOpsState(key, value);
            }
        }
    }

    public boolean markStateFrozenIfActive(String key) {
        int changed = jdbc.sql("""
                UPDATE ops_state
                   SET state_value = 'true', updated_at = CURRENT_TIMESTAMP
                 WHERE state_key = :key
                   AND LOWER(TRIM(state_value)) = 'false'
                """)
                .param("key", key)
                .update();
        if (changed == 1) {
            return true;
        }
        if (findOpsState(key).isPresent()) {
            return false;
        }
        try {
            jdbc.sql("INSERT INTO ops_state (state_key, state_value) VALUES (:key, 'true')")
                    .param("key", key)
                    .update();
            return true;
        } catch (DuplicateKeyException concurrentInsert) {
            return jdbc.sql("""
                    UPDATE ops_state
                       SET state_value = 'true', updated_at = CURRENT_TIMESTAMP
                     WHERE state_key = :key
                       AND LOWER(TRIM(state_value)) = 'false'
                    """)
                    .param("key", key)
                    .update() == 1;
        }
    }

    public boolean markStateReleasedIfFrozen(String key) {
        return jdbc.sql("""
                UPDATE ops_state
                   SET state_value = 'false', updated_at = CURRENT_TIMESTAMP
                 WHERE state_key = :key
                   AND LOWER(TRIM(state_value)) = 'true'
                """)
                .param("key", key)
                .update() == 1;
    }

    public void deleteOpsState(String key) {
        jdbc.sql("DELETE FROM ops_state WHERE state_key = :key")
                .param("key", key)
                .update();
    }

    public void insertOpsAction(OpsActionDraft action) {
        jdbc.sql("""
                INSERT INTO ops_action_log
                  (action_type, reason, trigger_type, previous_state, new_state)
                VALUES
                  (:actionType, :reason, :triggerType, :previousState, :newState)
                """)
                .param("actionType", action.actionType())
                .param("reason", action.reason())
                .param("triggerType", action.triggerType())
                .param("previousState", action.previousState())
                .param("newState", action.newState())
                .update();
    }

    public OpsOverview findOpsOverview(boolean frozen) {
        record StateEntry(String key, OpsState state) {
        }
        List<String> stateKeys = List.of(
                "FREEZE_REASON", "FREEZE_TRIGGER", "FREEZE_AT", "FEED_DEADMAN_STATUS");
        java.util.Map<String, OpsState> states = jdbc.sql("""
                SELECT state_key, state_value, updated_at
                  FROM ops_state
                 WHERE state_key IN (:stateKeys)
                """)
                .param("stateKeys", stateKeys)
                .query((rs, rowNum) -> new StateEntry(
                        rs.getString("state_key"),
                        new OpsState(
                                rs.getString("state_value"),
                                rs.getTimestamp("updated_at").toInstant())))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        StateEntry::key, StateEntry::state));

        OpsOverview.GoldenRun latestGolden = jdbc.sql("""
                SELECT id, mode, run_status, dataset_version, prompt_version,
                       model_version, total_count, matched_count, failed_count,
                       agreement, started_at, completed_at
                  FROM golden_set_run
                 ORDER BY started_at DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .query((rs, rowNum) -> new OpsOverview.GoldenRun(
                        rs.getLong("id"),
                        rs.getString("mode"),
                        rs.getString("run_status"),
                        rs.getString("dataset_version"),
                        rs.getString("prompt_version"),
                        rs.getString("model_version"),
                        rs.getInt("total_count"),
                        rs.getInt("matched_count"),
                        rs.getInt("failed_count"),
                        nullableDouble(rs, "agreement"),
                        rs.getTimestamp("started_at").toInstant(),
                        nullableInstant(rs.getTimestamp("completed_at"))))
                .optional()
                .orElse(null);

        List<OpsOverview.ControlMetric> metrics = jdbc.sql("""
                SELECT metric_date, metric_code, metric_value, baseline_mean,
                       lower_bound, upper_bound, monitor_status, violation,
                       consecutive_violations, sample_days
                  FROM (
                    SELECT p.*,
                           ROW_NUMBER() OVER (
                             PARTITION BY metric_code
                             ORDER BY metric_date DESC, id DESC) AS row_rank
                      FROM pipeline_metric_daily p
                 ) ranked
                 WHERE row_rank = 1
                 ORDER BY metric_code
                 FETCH FIRST 4 ROWS ONLY
                """)
                .query((rs, rowNum) -> new OpsOverview.ControlMetric(
                        rs.getDate("metric_date").toLocalDate(),
                        rs.getString("metric_code"),
                        rs.getDouble("metric_value"),
                        nullableDouble(rs, "baseline_mean"),
                        nullableDouble(rs, "lower_bound"),
                        nullableDouble(rs, "upper_bound"),
                        rs.getString("monitor_status"),
                        "Y".equals(rs.getString("violation")),
                        rs.getInt("consecutive_violations"),
                        rs.getInt("sample_days")))
                .list();

        return new OpsOverview(
                frozen,
                stateValue(states, "FREEZE_REASON"),
                stateValue(states, "FREEZE_TRIGGER"),
                parseInstant(stateValue(states, "FREEZE_AT")),
                latestGolden,
                metrics,
                deadmanSummary(stateValue(states, "FEED_DEADMAN_STATUS")));
    }

    private static String stateValue(java.util.Map<String, OpsState> states, String key) {
        OpsState state = states.get(key);
        return state == null ? null : state.value();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static OpsOverview.DeadmanSummary deadmanSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return new OpsOverview.DeadmanSummary(
                    "NOT_RECORDED", null, 0, 0, 0, List.of());
        }
        try {
            JsonNode root = OPS_JSON.readTree(raw);
            JsonNode feedNodes = root.get("feeds");
            if (feedNodes == null || !feedNodes.isArray()
                    || feedNodes.size() > MAX_DEADMAN_SOURCES) {
                return invalidDeadman();
            }
            List<OpsOverview.DeadmanFeed> feeds = new java.util.ArrayList<>();
            int alerts = 0;
            int insufficient = 0;
            for (JsonNode feed : feedNodes) {
                String source = boundedJsonText(feed, "source", 80);
                String status = boundedJsonText(feed, "status", 24);
                if (source == null || status == null) {
                    return invalidDeadman();
                }
                if ("ALERT".equals(status)) {
                    alerts++;
                } else if ("INSUFFICIENT_DATA".equals(status)) {
                    insufficient++;
                } else if (!"OK".equals(status)) {
                    return invalidDeadman();
                }
                feeds.add(new OpsOverview.DeadmanFeed(
                        source,
                        status,
                        Math.max(0, feed.path("intervalSamples").asInt(0)),
                        nullableJsonDouble(feed.get("medianIntervalHours")),
                        nullableJsonDouble(feed.get("silenceHours"))));
            }
            String status = alerts > 0
                    ? "ALERT"
                    : feeds.isEmpty()
                            ? "NO_FEEDS"
                            : insufficient == feeds.size() ? "INSUFFICIENT_DATA" : "OK";
            String observedAt = boundedJsonText(root, "observedAt", 40);
            if (observedAt != null && parseInstant(observedAt) == null) {
                observedAt = null;
            }
            return new OpsOverview.DeadmanSummary(
                    status, observedAt, feeds.size(), alerts, insufficient, feeds);
        } catch (RuntimeException invalid) {
            return invalidDeadman();
        } catch (java.io.IOException invalid) {
            return invalidDeadman();
        }
    }

    private static OpsOverview.DeadmanSummary invalidDeadman() {
        return new OpsOverview.DeadmanSummary("INVALID", null, 0, 0, 0, List.of());
    }

    private static String boundedJsonText(JsonNode node, String field, int maxLength) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.length() <= maxLength ? text : null;
    }

    private static Double nullableJsonDouble(JsonNode value) {
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        double number = value.asDouble();
        return Double.isFinite(number) && number >= 0 ? number : null;
    }

    public List<GoldenSetItemRow> findActiveGoldenSetItems(
            String datasetVersion, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 60);
        return jdbc.sql("""
                SELECT id, case_code, fixture_kind, title, body, expected_output,
                       expected_schema_version, dataset_version, input_sha256
                  FROM golden_set_item
                 WHERE dataset_version = :datasetVersion
                   AND active = 'Y'
                 ORDER BY case_code
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .param("datasetVersion", datasetVersion)
                .query((rs, rowNum) -> new GoldenSetItemRow(
                        rs.getLong("id"),
                        rs.getString("case_code"),
                        rs.getString("fixture_kind"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("expected_output"),
                        rs.getString("expected_schema_version"),
                        rs.getString("dataset_version"),
                        rs.getString("input_sha256").trim()))
                .list();
    }

    public long insertGoldenRun(GoldenRunDraft draft) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO golden_set_run
                  (mode, dataset_version, prompt_version, model_version,
                   rubric_version_id, expected_schema_version, run_status,
                   total_count, matched_count, failed_count)
                VALUES
                  (:mode, :datasetVersion, :promptVersion, :modelVersion,
                   :rubricVersionId, :expectedSchemaVersion, 'RUNNING',
                   :totalCount, 0, 0)
                """)
                .param("mode", draft.mode())
                .param("datasetVersion", draft.datasetVersion())
                .param("promptVersion", draft.promptVersion())
                .param("modelVersion", draft.modelVersion())
                .param("rubricVersionId", draft.rubricVersionId())
                .param("expectedSchemaVersion", draft.expectedSchemaVersion())
                .param("totalCount", draft.totalCount())
                .update(keys, "id");
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Golden run insert produced no generated key");
        }
        return key.longValue();
    }

    public void insertGoldenResult(GoldenResultRow result) {
        jdbc.sql("""
                INSERT INTO golden_set_result
                  (run_id, item_id, actual_output_sha256, matched,
                   mismatch_fields, error_code)
                VALUES
                  (:runId, :itemId, :actualOutputSha256, :matched,
                   :mismatchFields, :errorCode)
                """)
                .param("runId", result.runId())
                .param("itemId", result.itemId())
                .param("actualOutputSha256", result.actualOutputSha256(), Types.CHAR)
                .param("matched", result.matched() ? "Y" : "N")
                .param("mismatchFields", result.mismatchFields(), Types.VARCHAR)
                .param("errorCode", result.errorCode(), Types.VARCHAR)
                .update();
    }

    public void completeGoldenRun(
            long runId,
            String status,
            int matchedCount,
            int failedCount,
            double agreement) {
        int changed = jdbc.sql("""
                UPDATE golden_set_run
                   SET run_status = :status,
                       matched_count = :matchedCount,
                       failed_count = :failedCount,
                       agreement = :agreement,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE id = :runId AND run_status = 'RUNNING'
                """)
                .param("status", status)
                .param("matchedCount", matchedCount)
                .param("failedCount", failedCount)
                .param("agreement", agreement)
                .param("runId", runId)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Golden run is not RUNNING: " + runId);
        }
    }

    public PipelineDailyAggregate aggregatePipelineMetrics(LocalDate metricDate) {
        if (metricDate == null) {
            throw new IllegalArgumentException("metric date is required");
        }
        Timestamp start = Timestamp.from(metricDate.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp end = Timestamp.from(metricDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        record GateCounts(long passed, long total) {
        }
        GateCounts gate = jdbc.sql("""
                SELECT SUM(CASE
                           WHEN pipeline_status IN ('GATE_PASSED','CLASSIFIED')
                           THEN 1 ELSE 0 END) AS passed_count,
                       COUNT(*) AS total_count
                  FROM article
                 WHERE fetched_at >= :startAt AND fetched_at < :endAt
                   AND pipeline_status IN
                       ('GATE_PASSED','CLASSIFIED','GATE_REJECTED')
                """)
                .param("startAt", start)
                .param("endAt", end)
                .query((rs, rowNum) -> new GateCounts(
                        rs.getLong("passed_count"), rs.getLong("total_count")))
                .single();
        double gateRate = gate.total() == 0
                ? 0.0 : (double) gate.passed() / gate.total();

        long confirmed = jdbc.sql("""
                SELECT COUNT(*) FROM event
                 WHERE event_status = 'CONFIRMED'
                   AND updated_at >= :startAt AND updated_at < :endAt
                """)
                .param("startAt", start)
                .param("endAt", end)
                .query(Long.class)
                .single();

        List<Double> impactScores = jdbc.sql("""
                SELECT impact_score FROM event
                 WHERE impact_score IS NOT NULL
                   AND updated_at >= :startAt AND updated_at < :endAt
                 ORDER BY impact_score
                 FETCH FIRST 10001 ROWS ONLY
                """)
                .param("startAt", start)
                .param("endAt", end)
                .query(Double.class)
                .list();
        if (impactScores.size() > 10_000) {
            throw new IllegalStateException("daily impact metric exceeds 10000 rows");
        }
        double median = percentile(impactScores, 0.50);
        double p95 = percentile(impactScores, 0.95);
        return new PipelineDailyAggregate(gateRate, confirmed, median, p95);
    }

    public List<FeedPublicationWindow> findActiveFeedPublicationWindows(int timestampLimit) {
        if (timestampLimit <= 0) {
            return List.of();
        }
        int safeTimestampLimit = Math.min(
                timestampLimit, MAX_DEADMAN_TIMESTAMPS_PER_SOURCE);
        record FeedSource(long id, String code) {
        }
        List<FeedSource> sources = jdbc.sql("""
                SELECT id, code
                  FROM source_registry
                 WHERE feed_active = 'Y'
                   AND rss_url IS NOT NULL
                 ORDER BY code
                 FETCH FIRST %d ROWS ONLY
                """.formatted(MAX_DEADMAN_SOURCES + 1))
                .query((rs, rowNum) -> new FeedSource(
                        rs.getLong("id"), rs.getString("code")))
                .list();
        if (sources.size() > MAX_DEADMAN_SOURCES) {
            throw new IllegalStateException("active feed count exceeds deadman limit of 16");
        }

        return sources.stream()
                .map(source -> new FeedPublicationWindow(
                        source.id(),
                        source.code(),
                        jdbc.sql("""
                                SELECT published_at
                                  FROM article
                                 WHERE source_id = :sourceId
                                   AND published_at IS NOT NULL
                                 ORDER BY published_at DESC
                                 FETCH FIRST %d ROWS ONLY
                                """.formatted(safeTimestampLimit))
                                .param("sourceId", source.id())
                                .query((rs, rowNum) -> rs.getTimestamp("published_at").toInstant())
                                .list()))
                .toList();
    }

    public List<PipelineMetricRow> findRecentPipelineMetrics(
            String metricCode,
            LocalDate beforeExclusive,
            int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 28);
        return jdbc.sql("""
                SELECT metric_date, metric_code, metric_value, baseline_mean,
                       lower_bound, upper_bound, monitor_status, violation,
                       consecutive_violations, sample_days
                  FROM pipeline_metric_daily
                 WHERE metric_code = :metricCode
                   AND metric_date < :beforeExclusive
                 ORDER BY metric_date DESC
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .param("metricCode", metricCode)
                .param("beforeExclusive", date(beforeExclusive))
                .query((rs, rowNum) -> new PipelineMetricRow(
                        rs.getDate("metric_date").toLocalDate(),
                        rs.getString("metric_code"),
                        rs.getDouble("metric_value"),
                        nullableDouble(rs, "baseline_mean"),
                        nullableDouble(rs, "lower_bound"),
                        nullableDouble(rs, "upper_bound"),
                        rs.getString("monitor_status"),
                        "Y".equals(rs.getString("violation")),
                        rs.getInt("consecutive_violations"),
                        rs.getInt("sample_days")))
                .list();
    }

    public void upsertPipelineMetric(PipelineMetricRow row) {
        int changed = jdbc.sql("""
                UPDATE pipeline_metric_daily
                   SET metric_value = :metricValue,
                       baseline_mean = :baselineMean,
                       lower_bound = :lowerBound,
                       upper_bound = :upperBound,
                       monitor_status = :monitorStatus,
                       violation = :violation,
                       consecutive_violations = :consecutiveViolations,
                       sample_days = :sampleDays,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE metric_date = :metricDate AND metric_code = :metricCode
                """)
                .param("metricValue", row.metricValue())
                .param("baselineMean", row.baselineMean(), Types.NUMERIC)
                .param("lowerBound", row.lowerBound(), Types.NUMERIC)
                .param("upperBound", row.upperBound(), Types.NUMERIC)
                .param("monitorStatus", row.monitorStatus())
                .param("violation", row.violation() ? "Y" : "N")
                .param("consecutiveViolations", row.consecutiveViolations())
                .param("sampleDays", row.sampleDays())
                .param("metricDate", date(row.metricDate()))
                .param("metricCode", row.metricCode())
                .update();
        if (changed == 1) {
            return;
        }
        try {
            jdbc.sql("""
                    INSERT INTO pipeline_metric_daily
                      (metric_date, metric_code, metric_value, baseline_mean,
                       lower_bound, upper_bound, monitor_status, violation,
                       consecutive_violations, sample_days)
                    VALUES
                      (:metricDate, :metricCode, :metricValue, :baselineMean,
                       :lowerBound, :upperBound, :monitorStatus, :violation,
                       :consecutiveViolations, :sampleDays)
                    """)
                    .param("metricDate", date(row.metricDate()))
                    .param("metricCode", row.metricCode())
                    .param("metricValue", row.metricValue())
                    .param("baselineMean", row.baselineMean(), Types.NUMERIC)
                    .param("lowerBound", row.lowerBound(), Types.NUMERIC)
                    .param("upperBound", row.upperBound(), Types.NUMERIC)
                    .param("monitorStatus", row.monitorStatus())
                    .param("violation", row.violation() ? "Y" : "N")
                    .param("consecutiveViolations", row.consecutiveViolations())
                    .param("sampleDays", row.sampleDays())
                    .update();
        } catch (DuplicateKeyException concurrentInsert) {
            upsertPipelineMetric(row);
        }
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        double position = percentile * (sortedValues.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double fraction = position - lower;
        return sortedValues.get(lower)
                + fraction * (sortedValues.get(upper) - sortedValues.get(lower));
    }

    public List<TimelineRow> findEventTimeline(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 200);
        record TimelineSkeleton(
                long eventId,
                LocalDate occurredOn,
                String nodeName,
                String eventType,
                Integer levelFrom,
                Integer levelTo,
                Double impactScore,
                String verificationLevel) {
        }
        List<TimelineSkeleton> skeletons = jdbc.sql("""
                SELECT e.id AS event_id, e.occurred_on, n.name_ko, e.event_type,
                       h.prev_level, h.new_level,
                       e.impact_score, e.verification_level
                  FROM event e
                  JOIN capability_node n ON n.id = e.node_id
                  LEFT JOIN node_state_history h ON h.cause_event_id = e.id
                 WHERE e.event_status IN ('PROVISIONAL', 'CONFIRMED')
                 ORDER BY e.occurred_on DESC, e.id DESC
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query((rs, rowNum) -> new TimelineSkeleton(
                        rs.getLong("event_id"),
                        localDate(rs.getDate("occurred_on")),
                        rs.getString("name_ko"),
                        rs.getString("event_type"),
                        nullableInt(rs, "prev_level"),
                        nullableInt(rs, "new_level"),
                        nullableDouble(rs, "impact_score"),
                        rs.getString("verification_level")))
                .list();
        if (skeletons.isEmpty()) {
            return List.of();
        }

        record EvidenceRow(
                long eventId,
                long sourceId,
                String occurredOnPrecision,
                ReviewEvidence evidence) {
        }
        List<Long> eventIds = skeletons.stream().map(TimelineSkeleton::eventId).toList();
        List<EvidenceRow> evidenceRows = new java.util.ArrayList<>(jdbc.sql("""
                SELECT c.event_id, a.source_id,
                       COALESCE(a.title, s.name) AS source_label,
                       a.url, c.evidence_quote
                  FROM article_classification c
                  JOIN article a ON a.id = c.article_id
                  JOIN source_registry s ON s.id = a.source_id
                 WHERE c.quote_verified = 'Y'
                   AND c.event_id IN (:eventIds)
                 ORDER BY c.id
                """)
                .param("eventIds", eventIds)
                .query((rs, rowNum) -> new EvidenceRow(
                        rs.getLong("event_id"),
                        rs.getLong("source_id"),
                        null,
                        new ReviewEvidence(
                                EvidenceKind.VERBATIM,
                                rs.getString("source_label"),
                                rs.getString("url"),
                                rs.getString("evidence_quote"),
                                null,
                                null,
                                null)))
                .list());
        evidenceRows.addAll(jdbc.sql("""
                SELECT h.event_id, h.source_id, h.occurred_on_precision,
                       s.name, h.url, h.fact_summary, h.locator, h.accessed_on
                  FROM historical_evidence h
                  JOIN source_registry s ON s.id = h.source_id
                 WHERE h.event_id IN (:eventIds)
                   AND h.fact_review_status = 'APPROVED'
                   AND h.rubric_review_status = 'APPROVED'
                   AND h.reference_status = 'APPROVED'
                 ORDER BY h.id
                """)
                .param("eventIds", eventIds)
                .query((rs, rowNum) -> new EvidenceRow(
                        rs.getLong("event_id"),
                        rs.getLong("source_id"),
                        rs.getString("occurred_on_precision"),
                        new ReviewEvidence(
                                EvidenceKind.HISTORICAL_REFERENCE,
                                rs.getString("name"),
                                rs.getString("url"),
                                null,
                                rs.getString("fact_summary"),
                                rs.getString("locator"),
                                localDate(rs.getDate("accessed_on")))))
                .list());

        java.util.Map<Long, List<ReviewEvidence>> evidenceByEvent = new java.util.HashMap<>();
        java.util.Map<Long, Set<Long>> sourcesByEvent = new java.util.HashMap<>();
        java.util.Map<Long, String> precisionByEvent = new java.util.HashMap<>();
        for (EvidenceRow row : evidenceRows) {
            evidenceByEvent.computeIfAbsent(row.eventId(), key -> new java.util.ArrayList<>())
                    .add(row.evidence());
            sourcesByEvent.computeIfAbsent(row.eventId(), key -> new java.util.HashSet<>())
                    .add(row.sourceId());
            if (row.occurredOnPrecision() != null) {
                precisionByEvent.putIfAbsent(row.eventId(), row.occurredOnPrecision());
            }
        }
        return skeletons.stream().map(skeleton -> {
            List<ReviewEvidence> evidence = evidenceByEvent.getOrDefault(
                    skeleton.eventId(), List.of());
            ReviewEvidence primary = evidence.isEmpty() ? null : evidence.getFirst();
            return new TimelineRow(
                    skeleton.occurredOn(),
                    precisionByEvent.getOrDefault(skeleton.eventId(), "DAY"),
                    skeleton.nodeName(),
                    skeleton.eventType(),
                    skeleton.levelFrom(),
                    skeleton.levelTo(),
                    skeleton.impactScore(),
                    skeleton.verificationLevel(),
                    sourcesByEvent.getOrDefault(skeleton.eventId(), Set.of()).size(),
                    primary == null ? null : primary.evidenceQuote(),
                    primary);
        }).toList();
    }

    /** Full bodies of quote-verified articles in an event's cluster (fluke input). */
    public List<String> findVerifiedEvidenceBodies(long eventId) {
        return jdbc.sql("""
                SELECT a.body
                  FROM article_classification c
                  JOIN article a ON a.id = c.article_id
                 WHERE c.event_id = :eventId
                   AND c.quote_verified = 'Y'
                 ORDER BY c.id
                """)
                .param("eventId", eventId)
                .query(String.class)
                .list();
    }

    public NodeRow findNodeByCode(String code) {
        return jdbc.sql(NODE_SELECT + " WHERE code = :code")
                .param("code", code)
                .query(TrackerRepository::mapNode)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability node: " + code));
    }

    @Transactional
    public void advanceNode(
            long nodeId,
            int newLevel,
            String verification,
            long causeEventId,
            long rubricVersionId) {
        if (newLevel < 0 || newLevel > 9) {
            throw new IllegalArgumentException("Node level must be between 0 and 9");
        }
        NodeRow previous = findNodeById(nodeId);
        int changed = jdbc.sql("""
                UPDATE capability_node
                   SET current_level = :newLevel,
                       verification_level = :verification,
                       node_status = 'ACTIVE',
                       dormant_since = NULL,
                       program_end_date = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :nodeId
                """)
                .param("newLevel", newLevel)
                .param("verification", verification)
                .param("nodeId", nodeId)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Node disappeared during update: " + nodeId);
        }
        jdbc.sql("""
                INSERT INTO node_state_history
                  (node_id, prev_level, new_level, prev_status, new_status,
                   verification_level, cause_event_id, rubric_version_id)
                VALUES
                  (:nodeId, :prevLevel, :newLevel, :prevStatus, 'ACTIVE',
                   :verification, :causeEventId, :rubricVersionId)
                """)
                .param("nodeId", nodeId)
                .param("prevLevel", previous.currentLevel())
                .param("newLevel", newLevel)
                .param("prevStatus", previous.nodeStatus())
                .param("verification", verification)
                .param("causeEventId", causeEventId)
                .param("rubricVersionId", rubricVersionId)
                .update();
    }

    public void recordProgramEndDate(long nodeId, LocalDate programEndDate) {
        int changed = jdbc.sql("""
                UPDATE capability_node
                   SET program_end_date = :programEndDate,
                       node_status = 'ACTIVE',
                       dormant_since = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :nodeId
                """)
                .param("programEndDate", date(programEndDate))
                .param("nodeId", nodeId)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown capability node id: " + nodeId);
        }
    }

    public void markNodeDormant(long nodeId, LocalDate dormantSince) {
        int changed = jdbc.sql("""
                UPDATE capability_node
                   SET node_status = 'DORMANT',
                       dormant_since = :dormantSince,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :nodeId
                """)
                .param("dormantSince", date(dormantSince))
                .param("nodeId", nodeId)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown capability node id: " + nodeId);
        }
    }

    private Optional<Long> articleIdByHash(String urlHash) {
        return jdbc.sql("SELECT id FROM article WHERE url_hash = :urlHash")
                .param("urlHash", urlHash)
                .query(Long.class)
                .optional();
    }

    private Optional<Long> eventIdByNaturalKey(String naturalKey) {
        return jdbc.sql("SELECT id FROM event WHERE natural_key = :naturalKey")
                .param("naturalKey", naturalKey)
                .query(Long.class)
                .optional();
    }

    private void setEventStatus(long eventId, String status, String stateAdvanced) {
        int changed = jdbc.sql("""
                UPDATE event
                   SET event_status = :status, state_advanced = :advanced, updated_at = CURRENT_TIMESTAMP
                 WHERE id = :id
                """)
                .param("status", status)
                .param("advanced", stateAdvanced)
                .param("id", eventId)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Unknown event id: " + eventId);
        }
    }

    public NodeRow findNodeById(long id) {
        return jdbc.sql(NODE_SELECT + " WHERE id = :id")
                .param("id", id)
                .query(TrackerRepository::mapNode)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability node id: " + id));
    }

    private static ArticleRow mapArticle(ResultSet rs, int rowNum) throws SQLException {
        Timestamp published = rs.getTimestamp("published_at");
        return new ArticleRow(
                rs.getLong("id"),
                rs.getLong("source_id"),
                rs.getString("url"),
                rs.getString("url_hash"),
                rs.getString("title"),
                published == null ? null : published.toInstant(),
                rs.getTimestamp("fetched_at").toInstant(),
                rs.getString("body"),
                "Y".equals(rs.getString("body_extracted")),
                rs.getString("body_extraction_status"),
                rs.getString("pipeline_status"),
                rs.getInt("fail_count"));
    }

    private static EventRow mapEvent(ResultSet rs, int rowNum) throws SQLException {
        int claimedLevel = rs.getInt("claimed_level");
        boolean levelNull = rs.wasNull();
        double impact = rs.getDouble("impact_score");
        boolean impactNull = rs.wasNull();
        int novelty = rs.getInt("novelty");
        boolean noveltyNull = rs.wasNull();
        return new EventRow(
                rs.getLong("id"),
                rs.getString("natural_key"),
                rs.getLong("node_id"),
                rs.getString("event_type"),
                levelNull ? null : claimedLevel,
                rs.getString("actor"),
                localDate(rs.getDate("occurred_on")),
                rs.getString("verification_level"),
                rs.getString("event_status"),
                localDate(rs.getDate("provisional_expires_on")),
                impactNull ? null : impact,
                noveltyNull ? null : novelty,
                "Y".equals(rs.getString("state_advanced")),
                rs.getLong("rubric_version_id"));
    }

    private static ReviewRow mapReview(ResultSet rs, int rowNum) throws SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new ReviewRow(
                rs.getLong("id"),
                rs.getLong("event_id"),
                rs.getString("reason"),
                rs.getString("fluke_result"),
                rs.getString("status"),
                rs.getString("reviewer_note"),
                rs.getInt("priority"),
                rs.getString("fluke_status"),
                rs.getInt("fluke_fail_count"),
                rs.getString("fluke_last_error"),
                rs.getTimestamp("created_at").toInstant(),
                resolved == null ? null : resolved.toInstant());
    }

    private static SnapshotRow mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return new SnapshotRow(
                rs.getLong("id"),
                rs.getInt("pillar"),
                localDate(rs.getDate("snapshot_date")),
                rs.getDouble("readiness"),
                rs.getDouble("logit_clipped"),
                nullableDouble(rs, "trend_fit"),
                nullableDouble(rs, "trend_used"),
                nullableInt(rs, "n_events_window"),
                nullableInt(rs, "window_years"),
                nullableDouble(rs, "eta_year"),
                nullableDouble(rs, "eta_low"),
                nullableDouble(rs, "eta_high"),
                nullableDouble(rs, "displayed_eta_year"),
                rs.getString("params_version"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static ClassificationRow mapClassification(ResultSet rs, int rowNum) throws SQLException {
        long eventId = rs.getLong("event_id");
        boolean eventIdNull = rs.wasNull();
        int claimedLevel = rs.getInt("claimed_level");
        boolean levelNull = rs.wasNull();
        return new ClassificationRow(
                rs.getLong("id"),
                rs.getLong("article_id"),
                eventIdNull ? null : eventId,
                rs.getString("node_code"),
                rs.getString("event_type"),
                levelNull ? null : claimedLevel,
                rs.getString("actor"),
                localDate(rs.getDate("occurred_on")),
                rs.getString("publication_path"),
                rs.getString("evidence_quote"),
                "Y".equals(rs.getString("quote_verified")),
                rs.getString("raw_output"),
                rs.getLong("rubric_version_id"));
    }

    private static NodeRow mapNode(ResultSet rs, int rowNum) throws SQLException {
        return new NodeRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getInt("pillar"),
                rs.getString("name_ko"),
                rs.getString("scale_type"),
                rs.getInt("current_level"),
                rs.getString("verification_level"),
                rs.getString("node_status"),
                localDate(rs.getDate("dormant_since")),
                localDate(rs.getDate("program_end_date")),
                rs.getDouble("weight"),
                "Y".equals(rs.getString("is_integration_node")),
                rs.getString("description"),
                rs.getString("node_set_version"));
    }

    private static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static final String EVENT_SELECT = """
            SELECT id, natural_key, node_id, event_type, claimed_level, actor, occurred_on,
                   verification_level, event_status, provisional_expires_on,
                   impact_score, novelty, state_advanced, rubric_version_id
              FROM event
            """;

    private static final String REVIEW_SELECT = """
            SELECT id, event_id, reason, fluke_result, status, reviewer_note,
                   priority, fluke_status, fluke_fail_count, fluke_last_error,
                   created_at, resolved_at
              FROM review_queue
            """;

    private static final String SNAPSHOT_SELECT = """
            SELECT id, pillar, snapshot_date, readiness, logit_clipped, trend_fit, trend_used,
                   n_events_window, window_years, eta_year, eta_low, eta_high,
                   displayed_eta_year, params_version
              FROM pillar_snapshot
            """;

    private static final String NODE_SELECT = """
            SELECT id, code, pillar, name_ko, scale_type, current_level,
                   verification_level, node_status, dormant_since, program_end_date,
                   weight, is_integration_node, description, node_set_version
              FROM capability_node
            """;
}
