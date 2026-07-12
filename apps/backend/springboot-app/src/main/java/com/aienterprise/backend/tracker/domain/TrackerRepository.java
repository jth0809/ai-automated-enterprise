package com.aienterprise.backend.tracker.domain;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
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
        if (articleIdByHash(urlHash).isPresent()) {
            return Optional.empty();
        }
        try {
            jdbc.sql("""
                    INSERT INTO article
                      (source_id, url, url_hash, title, published_at, body, body_extracted, pipeline_status)
                    VALUES
                      (:sourceId, :url, :urlHash, :title, :publishedAt, :body, 'N', 'INGESTED')
                    """)
                    .param("sourceId", sourceId)
                    .param("url", url)
                    .param("urlHash", urlHash)
                    .param("title", title)
                    .param("publishedAt", publishedAt == null ? null : Timestamp.from(publishedAt))
                    .param("body", body)
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
        return jdbc.sql("""
                SELECT id, source_id, url, url_hash, title, published_at, fetched_at,
                       body, body_extracted, pipeline_status, fail_count
                  FROM article
                 WHERE pipeline_status = :status
                 ORDER BY fetched_at, id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(safeLimit))
                .param("status", status)
                .query(TrackerRepository::mapArticle)
                .list();
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

    private NodeRow findNodeById(long id) {
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
                rs.getString("pipeline_status"),
                rs.getInt("fail_count"));
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

    private static final String NODE_SELECT = """
            SELECT id, code, pillar, name_ko, scale_type, current_level,
                   verification_level, node_status, dormant_since, program_end_date,
                   weight, is_integration_node, description, node_set_version
              FROM capability_node
            """;
}
