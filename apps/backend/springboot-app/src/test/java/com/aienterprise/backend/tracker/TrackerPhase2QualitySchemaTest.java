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
class TrackerPhase2QualitySchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void qualityTablesAndRequiredColumnsExist() {
        assertEquals(6, jdbc.sql("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                 WHERE TABLE_NAME IN (
                   'GOLDEN_SET_ITEM','GOLDEN_SET_DATASET','GOLDEN_SET_RUN',
                   'GOLDEN_SET_RESULT','PIPELINE_METRIC_DAILY','OPS_ACTION_LOG')
                """).query(Integer.class).single());

        assertColumns("GOLDEN_SET_ITEM", Set.of(
                "CASE_CODE", "FIXTURE_KIND", "EXPECTED_SCHEMA_VERSION",
                "RUBRIC_VERSION_ID", "DATASET_VERSION", "PROVENANCE_REFS",
                "INPUT_SHA256", "ACTIVE", "UPDATED_AT"));
        assertColumns("GOLDEN_SET_DATASET", Set.of(
                "DATASET_VERSION", "DATASET_SHA256", "ITEM_COUNT", "LOADED_AT"));
        assertColumns("GOLDEN_SET_RUN", Set.of(
                "MODE", "DATASET_VERSION", "PROMPT_VERSION", "MODEL_VERSION",
                "RUBRIC_VERSION_ID", "EXPECTED_SCHEMA_VERSION", "RUN_STATUS",
                "TOTAL_COUNT", "MATCHED_COUNT", "FAILED_COUNT", "AGREEMENT",
                "STARTED_AT", "COMPLETED_AT"));
        assertColumns("GOLDEN_SET_RESULT", Set.of(
                "RUN_ID", "ITEM_ID", "ACTUAL_OUTPUT_SHA256", "MATCHED",
                "MISMATCH_FIELDS", "ERROR_CODE", "CREATED_AT"));
        assertColumns("PIPELINE_METRIC_DAILY", Set.of(
                "METRIC_DATE", "METRIC_CODE", "METRIC_VALUE", "BASELINE_MEAN",
                "LOWER_BOUND", "UPPER_BOUND", "MONITOR_STATUS", "VIOLATION",
                "CONSECUTIVE_VIOLATIONS", "SAMPLE_DAYS", "UPDATED_AT"));
        assertColumns("OPS_ACTION_LOG", Set.of(
                "ACTION_TYPE", "REASON", "TRIGGER_TYPE", "PREVIOUS_STATE",
                "NEW_STATE", "CREATED_AT"));
    }

    @Test
    void foreignKeysUniquenessAndIndexesProtectTheLedger() {
        assertForeignKeys("GOLDEN_SET_ITEM", 2);
        assertForeignKeys("GOLDEN_SET_RUN", 2);
        assertForeignKeys("GOLDEN_SET_RESULT", 2);

        Set<String> indexes = new HashSet<>(jdbc.sql("""
                SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES
                 WHERE INDEX_NAME IN (
                   'IX_GOLDEN_ITEM_ACTIVE','IX_GOLDEN_RUN_STARTED',
                   'IX_GOLDEN_RESULT_RUN','IX_PIPELINE_METRIC_CODE_DATE',
                   'IX_OPS_ACTION_CREATED')
                """).query(String.class).list());
        assertEquals(Set.of(
                "IX_GOLDEN_ITEM_ACTIVE", "IX_GOLDEN_RUN_STARTED",
                "IX_GOLDEN_RESULT_RUN", "IX_PIPELINE_METRIC_CODE_DATE",
                "IX_OPS_ACTION_CREATED"), indexes);

        insertDataset("golden-v1", "a".repeat(64));
        assertThrows(DuplicateKeyException.class,
                () -> insertDataset("golden-v2", "a".repeat(64)));
        insertItem("CASE-001", "SYNTHETIC");
        assertThrows(DuplicateKeyException.class,
                () -> insertItem("CASE-001", "HUMAN_PARAPHRASE"));
    }

    @Test
    void enumAndRangeChecksRejectInvalidQualityRows() {
        insertDataset("golden-v1", "b".repeat(64));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertItem("CASE-BAD", "COPIED_ARTICLE"));

        long rubricId = rubricId();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO golden_set_run
                  (mode, dataset_version, prompt_version, model_version,
                   rubric_version_id, expected_schema_version, run_status,
                   total_count, matched_count, failed_count, agreement)
                VALUES
                  ('UNMARKED_TEST', 'golden-v1', 'prompt-v1', 'offline',
                   :rubricId, 'golden-output-v1', 'SUCCEEDED', 50, 45, 0, 0.90)
                """).param("rubricId", rubricId).update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO pipeline_metric_daily
                  (metric_date, metric_code, metric_value, monitor_status,
                   violation, consecutive_violations, sample_days)
                VALUES
                  (DATE '2026-07-14', 'RELEVANCE_GATE_PASS_RATE', 1.2,
                   'OK', 'N', 0, 28)
                """).update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO ops_action_log
                  (action_type, reason, trigger_type, previous_state, new_state)
                VALUES
                  ('RELEASE', 'Automated release is forbidden.', 'AUTOMATIC',
                   'FROZEN', 'ACTIVE')
                """).update());
    }

    @Test
    void goldenBodyIsDocumentedAsAuthoredFixtureAndRawOutputsAreForbidden() {
        String bodyComment = jdbc.sql("""
                SELECT REMARKS FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = 'GOLDEN_SET_ITEM' AND COLUMN_NAME = 'BODY'
                """).query(String.class).single();
        assertTrue(bodyComment.contains("SYNTHETIC"));
        assertTrue(bodyComment.contains("HUMAN_PARAPHRASE"));
        assertTrue(bodyComment.contains("NO EXTERNAL SOURCE BODY"));

        Set<String> prohibited = Set.of(
                "SOURCE_BODY", "ARTICLE_BODY", "RAW_MODEL_OUTPUT", "MODEL_RESPONSE",
                "QUOTE", "EXCERPT", "HTML", "PDF", "ATTACHMENT");
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME IN (
                   'GOLDEN_SET_ITEM','GOLDEN_SET_DATASET','GOLDEN_SET_RUN',
                   'GOLDEN_SET_RESULT')
                   AND COLUMN_NAME IN (
                     'SOURCE_BODY','ARTICLE_BODY','RAW_MODEL_OUTPUT','MODEL_RESPONSE',
                     'QUOTE','EXCERPT','HTML','PDF','ATTACHMENT')
                """).query(Integer.class).single();
        assertEquals(0, count);
        assertTrue(prohibited.stream().noneMatch(this::qualityColumnExists));
    }

    @Test
    void newIdentifiersRemainBounded() {
        assertMaxLength("GOLDEN_SET_ITEM", "CASE_CODE", 80);
        assertMaxLength("GOLDEN_SET_ITEM", "FIXTURE_KIND", 24);
        assertMaxLength("GOLDEN_SET_ITEM", "EXPECTED_SCHEMA_VERSION", 40);
        assertMaxLength("GOLDEN_SET_ITEM", "DATASET_VERSION", 80);
        assertMaxLength("GOLDEN_SET_DATASET", "DATASET_VERSION", 80);
        assertMaxLength("GOLDEN_SET_RUN", "PROMPT_VERSION", 80);
        assertMaxLength("GOLDEN_SET_RUN", "MODEL_VERSION", 120);
        assertMaxLength("PIPELINE_METRIC_DAILY", "METRIC_CODE", 40);
    }

    private void insertDataset(String version, String sha) {
        jdbc.sql("""
                INSERT INTO golden_set_dataset
                  (dataset_version, dataset_sha256, item_count)
                VALUES (:version, :sha, 50)
                """)
                .param("version", version)
                .param("sha", sha)
                .update();
    }

    private void insertItem(String caseCode, String fixtureKind) {
        jdbc.sql("""
                INSERT INTO golden_set_item
                  (case_code, fixture_kind, title, body, expected_output,
                   expected_schema_version, rubric_version_id, dataset_version,
                   provenance_refs, input_sha256, active)
                VALUES
                  (:caseCode, :fixtureKind, 'Authored fixture',
                   'A short synthetic tracker event.',
                   '{"relevant":false}', 'golden-output-v1', :rubricId,
                   'golden-v1', '[]', :sha, 'Y')
                """)
                .param("caseCode", caseCode)
                .param("fixtureKind", fixtureKind)
                .param("rubricId", rubricId())
                .param("sha", "c".repeat(64))
                .update();
    }

    private long rubricId() {
        return jdbc.sql("SELECT id FROM rubric_version WHERE version_label = 'r2.0'")
                .query(Long.class).single();
    }

    private void assertColumns(String table, Set<String> required) {
        Set<String> columns = new HashSet<>(jdbc.sql("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = :tableName
                """).param("tableName", table).query(String.class).list());
        assertTrue(columns.containsAll(required),
                () -> table + " missing " + difference(required, columns));
    }

    private void assertForeignKeys(String table, int expected) {
        assertEquals(expected, jdbc.sql("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                 WHERE TABLE_NAME = :tableName AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """).param("tableName", table).query(Integer.class).single());
    }

    private void assertMaxLength(String table, String column, long expected) {
        Long actual = jdbc.sql("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = :tableName AND COLUMN_NAME = :columnName
                """)
                .param("tableName", table)
                .param("columnName", column)
                .query(Long.class)
                .single();
        assertEquals(expected, actual);
    }

    private boolean qualityColumnExists(String column) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME IN (
                   'GOLDEN_SET_ITEM','GOLDEN_SET_DATASET','GOLDEN_SET_RUN',
                   'GOLDEN_SET_RESULT')
                   AND COLUMN_NAME = :columnName
                """).param("columnName", column).query(Integer.class).single() > 0;
    }

    private static Set<String> difference(Set<String> required, Set<String> actual) {
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(actual);
        return missing;
    }
}
