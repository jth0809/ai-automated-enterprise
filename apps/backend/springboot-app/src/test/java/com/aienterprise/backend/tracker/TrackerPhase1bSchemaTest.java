package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TrackerPhase1bSchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void phase1bBodyExtractionSchemaExists() {
        assertColumn("ARTICLE", "BODY_EXTRACTION_STATUS");
        assertColumn("ARTICLE", "BODY_EXTRACTION_ATTEMPTS");
        assertColumn("ARTICLE", "BODY_EXTRACTION_ERROR");

        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                  FROM INFORMATION_SCHEMA.TABLES
                 WHERE TABLE_NAME = 'SOURCE_DOMAIN'
                """)
                .query(Integer.class)
                .single();
        assertEquals(1, count);
    }

    @Test
    void rssSummaryArticlesDefaultToSkippedExtraction() {
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'NASA'")
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO article
                  (source_id, url, url_hash, title, body, body_extracted, pipeline_status)
                VALUES
                  (:sourceId, 'https://example.test/legacy', :hash, 'Legacy', 'RSS summary', 'N', 'INGESTED')
                """)
                .param("sourceId", sourceId)
                .param("hash", "f".repeat(64))
                .update();

        String status = jdbc.sql("SELECT body_extraction_status FROM article WHERE url_hash = :hash")
                .param("hash", "f".repeat(64))
                .query(String.class)
                .single();

        assertEquals("SKIPPED", status);
    }

    @Test
    void oneSourceCannotDeclareTheSameDomainTwice() {
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'NASA'")
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO source_domain (source_id, domain, purpose, active)
                VALUES (:sourceId, 'example.test', 'BODY', 'Y')
                """)
                .param("sourceId", sourceId)
                .update();

        assertThrows(DuplicateKeyException.class, () -> jdbc.sql("""
                INSERT INTO source_domain (source_id, domain, purpose, active)
                VALUES (:sourceId, 'example.test', 'FEED', 'Y')
                """)
                .param("sourceId", sourceId)
                .update());
    }

    @Test
    void flukeReviewAuditSchemaExists() {
        assertColumn("REVIEW_QUEUE", "PRIORITY");
        assertColumn("REVIEW_QUEUE", "FLUKE_STATUS");
        assertColumn("REVIEW_QUEUE", "FLUKE_FAIL_COUNT");
        assertColumn("REVIEW_QUEUE", "FLUKE_LAST_ERROR");

        Integer table = jdbc.sql("""
                SELECT COUNT(*)
                  FROM INFORMATION_SCHEMA.TABLES
                 WHERE TABLE_NAME = 'FLUKE_EVALUATION'
                """)
                .query(Integer.class)
                .single();
        assertEquals(1, table);
    }

    @Test
    void oneReviewPerEventAndReasonWhileDistinctReasonsCoexist() {
        long eventId = insertEvent("P1-ORBIT-REFUEL|FLIGHT_TEST|schema-8|2926");
        insertReview(eventId, "HIGH_IMPACT");

        assertThrows(DuplicateKeyException.class, () -> insertReview(eventId, "HIGH_IMPACT"));

        insertReview(eventId, "ARRIVAL_CANDIDATE");
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM review_queue WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single());
    }

    private long insertEvent(String naturalKey) {
        jdbc.sql("""
                INSERT INTO event
                  (natural_key, node_id, event_type, claimed_level, actor, occurred_on,
                   verification_level, event_status, rubric_version_id)
                VALUES
                  (:key,
                   (SELECT id FROM capability_node WHERE code = 'P1-ORBIT-REFUEL'),
                   'FLIGHT_TEST', 6, 'SpaceX', DATE '2026-01-30',
                   'OFFICIAL', 'PROVISIONAL',
                   (SELECT id FROM rubric_version WHERE version_label = 'r1.0'))
                """)
                .param("key", naturalKey)
                .update();
        return jdbc.sql("SELECT id FROM event WHERE natural_key = :key")
                .param("key", naturalKey).query(Long.class).single();
    }

    private void insertReview(long eventId, String reason) {
        jdbc.sql("INSERT INTO review_queue (event_id, reason) VALUES (:eventId, :reason)")
                .param("eventId", eventId)
                .param("reason", reason)
                .update();
    }

    private void assertColumn(String table, String column) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                  FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = :tableName
                   AND COLUMN_NAME = :columnName
                """)
                .param("tableName", table)
                .param("columnName", column)
                .query(Integer.class)
                .single();
        assertTrue(count == 1, () -> table + "." + column + " is missing");
    }
}
