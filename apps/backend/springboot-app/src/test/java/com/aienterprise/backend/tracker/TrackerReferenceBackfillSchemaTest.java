package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TrackerReferenceBackfillSchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void referenceOnlyTablesAndForeignKeysExist() {
        Integer tables = jdbc.sql("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                 WHERE TABLE_NAME IN ('HISTORICAL_EVIDENCE','BACKFILL_IMPORT')
                """).query(Integer.class).single();
        assertEquals(2, tables);

        Integer evidenceForeignKeys = jdbc.sql("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                 WHERE TABLE_NAME = 'HISTORICAL_EVIDENCE'
                   AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """).query(Integer.class).single();
        assertEquals(2, evidenceForeignKeys);

        Integer importForeignKeys = jdbc.sql("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                 WHERE TABLE_NAME = 'BACKFILL_IMPORT'
                   AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """).query(Integer.class).single();
        assertEquals(1, importForeignKeys);
    }

    @Test
    void historicalEvidenceContainsOnlyReferenceMetadataAndReviewerSummary() {
        Set<String> columns = new HashSet<>(jdbc.sql("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = 'HISTORICAL_EVIDENCE'
                """).query(String.class).list());

        assertTrue(columns.containsAll(Set.of(
                "BACKFILL_ID", "CANDIDATE_ID", "OCCURRED_ON_PRECISION",
                "EVENT_ID", "SOURCE_ID", "URL",
                "LOCATOR", "ACCESSED_ON", "CONTENT_SHA256", "PUBLICATION_PATH",
                "FACT_SUMMARY", "FACT_REVIEW_STATUS", "RUBRIC_REVIEW_STATUS",
                "REFERENCE_STATUS", "REVIEWER_NOTE")));
        assertTrue(Set.of(
                "BODY", "BODY_TEXT", "BODY_HTML", "QUOTE", "EVIDENCE_QUOTE",
                "EXCERPT", "SOURCE_TITLE", "HTML", "ATTACHMENT")
                .stream().noneMatch(columns::contains));
    }

    @Test
    void historicalEvidenceChecksAndNaturalUniquenessAreEnforced() {
        long eventId = insertEvent("schema-reference-event");
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'NASA'")
                .query(Long.class).single();
        insertEvidence(eventId, sourceId, "BF-SCHEMA-1", "PRIMARY", "APPROVED", "APPROVED");

        assertThrows(DuplicateKeyException.class, () ->
                insertEvidence(eventId, sourceId, "BF-SCHEMA-1", "PRIMARY", "APPROVED", "APPROVED"));
        assertThrows(DataIntegrityViolationException.class, () ->
                insertEvidence(eventId, sourceId, "BF-SCHEMA-2", "SOCIAL", "APPROVED", "APPROVED"));
        assertThrows(DataIntegrityViolationException.class, () ->
                insertEvidence(eventId, sourceId, "BF-SCHEMA-3", "PRIMARY", "PENDING", "APPROVED"));
        assertThrows(DataIntegrityViolationException.class, () ->
                insertEvidence(eventId, sourceId, "BF-SCHEMA-4", "PRIMARY", "APPROVED", "PENDING"));
    }

    @Test
    void importAuditEnforcesDatasetHashUniquenessAndPositiveCount() {
        long rubricId = jdbc.sql("SELECT id FROM rubric_version WHERE version_label = 'r2.0'")
                .query(Long.class).single();
        insertImport("backfill-v1", "a".repeat(64), rubricId, 120);

        assertThrows(DuplicateKeyException.class, () ->
                insertImport("backfill-v1-copy", "a".repeat(64), rubricId, 120));
        assertThrows(DataIntegrityViolationException.class, () ->
                insertImport("backfill-empty", "b".repeat(64), rubricId, 0));
    }

    private long insertEvent(String suffix) {
        String naturalKey = "P1-ORBIT-REFUEL|FLIGHT_TEST|" + suffix + "|2026-W01";
        jdbc.sql("""
                INSERT INTO event
                  (natural_key, node_id, event_type, claimed_level, actor, occurred_on,
                   verification_level, event_status, rubric_version_id)
                VALUES
                  (:naturalKey,
                   (SELECT id FROM capability_node WHERE code = 'P1-ORBIT-REFUEL'),
                   'FLIGHT_TEST', 5, 'Schema test', DATE '2026-01-02',
                   'OFFICIAL', 'CONFIRMED',
                   (SELECT id FROM rubric_version WHERE version_label = 'r2.0'))
                """).param("naturalKey", naturalKey).update();
        return jdbc.sql("SELECT id FROM event WHERE natural_key = :naturalKey")
                .param("naturalKey", naturalKey).query(Long.class).single();
    }

    private void insertEvidence(
            long eventId,
            long sourceId,
            String backfillId,
            String publicationPath,
            String factReview,
            String rubricReview) {
        jdbc.sql("""
                INSERT INTO historical_evidence
                  (backfill_id, candidate_id, occurred_on_precision,
                   event_id, source_id, url, locator,
                   accessed_on, content_sha256, publication_path, fact_summary,
                   fact_review_status, rubric_review_status, reviewer_note)
                VALUES
                  (:backfillId, 'HC-SCHEMA', 'DAY', :eventId, :sourceId,
                   'https://www.nasa.gov/reference', 'official section',
                   DATE '2026-07-13', :sha, :publicationPath,
                   'Reviewer-authored factual summary.', :factReview, :rubricReview,
                   'Fact and rubric checked separately.')
                """)
                .param("backfillId", backfillId)
                .param("eventId", eventId)
                .param("sourceId", sourceId)
                .param("sha", "c".repeat(64))
                .param("publicationPath", publicationPath)
                .param("factReview", factReview)
                .param("rubricReview", rubricReview)
                .update();
    }

    private void insertImport(String version, String sha, long rubricId, int count) {
        jdbc.sql("""
                INSERT INTO backfill_import
                  (dataset_version, dataset_sha256, node_set_version,
                   rubric_version_id, record_count)
                VALUES (:version, :sha, 'nodes-v1.0', :rubricId, :recordCount)
                """)
                .param("version", version)
                .param("sha", sha)
                .param("rubricId", rubricId)
                .param("recordCount", count)
                .update();
    }
}
