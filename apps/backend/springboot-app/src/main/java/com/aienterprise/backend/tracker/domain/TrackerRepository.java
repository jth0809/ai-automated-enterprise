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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.event.SourceEvidence;

public class TrackerRepository {

    private final JdbcClient jdbc;

    public TrackerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        setEventStatus(eventId, "CONFIRMED", "Y");
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
        return jdbc.sql("""
                SELECT e.occurred_on, n.name_ko, e.event_type,
                       h.prev_level, h.new_level,
                       e.impact_score, e.verification_level,
                       (SELECT COUNT(DISTINCT a.source_id)
                          FROM article_classification c
                          JOIN article a ON a.id = c.article_id
                         WHERE c.event_id = e.id AND c.quote_verified = 'Y') AS source_count,
                       (SELECT c2.evidence_quote
                          FROM article_classification c2
                         WHERE c2.event_id = e.id AND c2.quote_verified = 'Y'
                         ORDER BY c2.id
                         FETCH FIRST 1 ROWS ONLY) AS evidence_quote
                  FROM event e
                  JOIN capability_node n ON n.id = e.node_id
                  LEFT JOIN node_state_history h ON h.cause_event_id = e.id
                 WHERE e.event_status IN ('PROVISIONAL', 'CONFIRMED')
                 ORDER BY e.occurred_on DESC, e.id DESC
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .query(TrackerRepository::mapTimeline)
                .list();
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

    private static TimelineRow mapTimeline(ResultSet rs, int rowNum) throws SQLException {
        return new TimelineRow(
                localDate(rs.getDate("occurred_on")),
                rs.getString("name_ko"),
                rs.getString("event_type"),
                nullableInt(rs, "prev_level"),
                nullableInt(rs, "new_level"),
                nullableDouble(rs, "impact_score"),
                rs.getString("verification_level"),
                rs.getInt("source_count"),
                rs.getString("evidence_quote"));
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
