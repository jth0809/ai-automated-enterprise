package com.aienterprise.backend.tracker.domain;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

public class TrackerRepository {

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
     * Pending review cases with full decision context, priority-desc then
     * oldest-first. One bounded case query plus one bounded evidence query,
     * grouped in Java — no N+1. Live quotations and approved historical
     * references are exposed as distinct evidence kinds.
     */
    public List<ReviewCase> findPendingReviewCases(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 200);
        List<ReviewCase> skeletons = jdbc.sql("""
                SELECT r.id AS review_id, r.reason, r.priority, r.fluke_status, r.fluke_result,
                       r.created_at, r.status, r.reviewer_note,
                       e.id AS event_id, e.event_type, e.occurred_on, e.actor,
                       e.verification_level, e.impact_score, e.claimed_level,
                       n.code AS node_code, n.name_ko, n.scale_type, n.current_level
                  FROM review_queue r
                  JOIN event e ON e.id = r.event_id
                  JOIN capability_node n ON n.id = e.node_id
                 WHERE r.status = 'PENDING'
                 ORDER BY r.priority DESC, r.created_at, r.id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query(TrackerRepository::mapReviewCaseSkeleton)
                .list();
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
                        skeleton.status(), skeleton.reviewerNote()))
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
                rs.getString("reviewer_note"));
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
