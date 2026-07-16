package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TrackerPhase4V19SchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void seedsThePredeclaredHazardContract() {
        assertEquals(1, count("""
                SELECT COUNT(*) FROM prediction_parameter_set
                 WHERE version_label = 'hazard-v1'
                   AND active = 'Y'
                   AND kappa_node_years = 4.0
                   AND probability_floor = 0.02
                   AND probability_ceiling = 0.98
                   AND horizons_months = '6,12,18,24'
                   AND cohort_limit = 12
                   AND pillar_limit = 2
                   AND calibration_min_outcomes = 30
                   AND calibration_min_quarters = 4
                """));
    }

    @Test
    void storesAnImmutableCompletedCohortAndResolutionAudit() {
        String calibrationVersion = insertIdentityCalibration("a".repeat(64));
        long cohortId = insertCohort("b".repeat(64), calibrationVersion, 1);
        long predictionId = insertPrediction(cohortId, calibrationVersion, "P1-REUSE-LV");

        jdbc.sql("""
                UPDATE prediction
                   SET outcome = 'MISS', brier = 0.16000000,
                       resolution_status = 'RESOLVED',
                       resolved_at = CURRENT_TIMESTAMP
                 WHERE id = :id
                """).param("id", predictionId).update();
        jdbc.sql("""
                INSERT INTO prediction_resolution_evidence
                  (prediction_id, outcome, outcome_binary, evidence_date,
                   resolved_at, resolver_version, reason_code, evidence_summary)
                VALUES
                  (:id, 'MISS', 0, DATE '2027-01-16', CURRENT_TIMESTAMP,
                   'resolver-v1', 'DUE_NO_TARGET',
                   'No confirmed target transition by the immutable due date.')
                """).param("id", predictionId).update();

        assertEquals(1, count("SELECT COUNT(*) FROM prediction_cohort"));
        assertEquals(1, count("SELECT COUNT(*) FROM prediction_resolution_evidence"));
    }

    @Test
    void rejectsMalformedOrDuplicatePhaseFourPredictions() {
        String calibrationVersion = insertIdentityCalibration("c".repeat(64));
        long cohortId = insertCohort("d".repeat(64), calibrationVersion, 1);
        insertPrediction(cohortId, calibrationVersion, "P1-REUSE-LV");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertPrediction(cohortId, calibrationVersion, "P1-REUSE-LV"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO prediction
                  (statement, node_id, probability, issued_on, due_on,
                   params_version, cohort_id, node_code, pillar, target_level,
                   horizon_months, raw_probability, calibrated_probability,
                   calibration_version, information_status, input_sha256,
                   statement_sha256, node_set_version, rubric_version)
                SELECT 'invalid horizon', id, 0.40, DATE '2026-07-16',
                       DATE '2027-04-16', 'hazard-v1', :cohortId, code, pillar,
                       6, 9, 0.40, 0.40, :calibrationVersion, 'INFORMATIVE',
                       :inputHash, :statementHash, node_set_version, 'r2.0'
                  FROM capability_node WHERE code = 'P1-ORBIT-REFUEL'
                """)
                .param("cohortId", cohortId)
                .param("calibrationVersion", calibrationVersion)
                .param("inputHash", "e".repeat(64))
                .param("statementHash", "f".repeat(64))
                .update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE prediction_cohort SET prediction_count = 13
                 WHERE id = :cohortId
                """).param("cohortId", cohortId).update());
    }

    @Test
    void enforcesBrierAndVoidEvidenceSemantics() {
        String calibrationVersion = insertIdentityCalibration("1".repeat(64));
        long cohortId = insertCohort("2".repeat(64), calibrationVersion, 1);
        long predictionId = insertPrediction(cohortId, calibrationVersion, "P2-ECLSS");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE prediction
                   SET outcome = 'VOID', brier = 0.25,
                       resolution_status = 'VOID', resolved_at = CURRENT_TIMESTAMP
                 WHERE id = :id
                """).param("id", predictionId).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO prediction_resolution_evidence
                  (prediction_id, outcome, outcome_binary, evidence_date,
                   resolved_at, resolver_version, reason_code, evidence_summary)
                VALUES
                  (:id, 'VOID', 0, DATE '2026-07-16', CURRENT_TIMESTAMP,
                   'resolver-v1', 'PROGRAM_CANCELLED', 'Invalid VOID reason')
                """).param("id", predictionId).update());
    }

    @Test
    void retainsTheOriginalPredictionInsertContract() {
        jdbc.sql("""
                INSERT INTO prediction
                  (statement, node_id, probability, issued_on, due_on,
                   params_version)
                SELECT 'legacy-compatible row', id, 0.50,
                       DATE '2026-07-16', DATE '2027-07-16', 'params-v2'
                  FROM capability_node WHERE code = 'P5-AUTONOMY'
                """).update();

        assertEquals(1, count("""
                SELECT COUNT(*) FROM prediction
                 WHERE statement = 'legacy-compatible row'
                   AND cohort_id IS NULL AND outcome = 'PENDING'
                """));
    }

    private String insertIdentityCalibration(String inputHash) {
        String version = "calibration-v1-" + inputHash.substring(0, 12);
        jdbc.sql("""
                INSERT INTO prediction_calibration_run
                  (calibration_version, input_sha256, method, calibration_status,
                   sample_count, quarter_count, knots_json, current_result)
                VALUES
                  (:version, :inputHash, 'IDENTITY',
                   'INSUFFICIENT_CALIBRATION_DATA', 0, 0, '[]', 'Y')
                """)
                .param("version", version)
                .param("inputHash", inputHash)
                .update();
        return version;
    }

    private long insertCohort(
            String inputHash, String calibrationVersion, int predictionCount) {
        String key = "micro-v1-" + inputHash.substring(0, 12);
        jdbc.sql("""
                INSERT INTO prediction_cohort
                  (cohort_key, input_sha256, dataset_sha256, node_set_version,
                   rubric_version, hazard_params_version, calibration_version,
                   as_of_date, issued_on, prediction_count, cohort_status,
                   completed_at)
                VALUES
                  (:key, :inputHash, :datasetHash, 'nodes-v1.0', 'r2.0',
                   'hazard-v1', :calibrationVersion, DATE '2026-07-13',
                   DATE '2026-07-16', :predictionCount, 'COMPLETED',
                   CURRENT_TIMESTAMP)
                """)
                .param("key", key)
                .param("inputHash", inputHash)
                .param("datasetHash", "9".repeat(64))
                .param("calibrationVersion", calibrationVersion)
                .param("predictionCount", predictionCount)
                .update();
        return jdbc.sql("SELECT id FROM prediction_cohort WHERE cohort_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private long insertPrediction(
            long cohortId, String calibrationVersion, String nodeCode) {
        String inputHash = nodeCode.equals("P1-REUSE-LV")
                ? "3".repeat(64) : "4".repeat(64);
        jdbc.sql("""
                INSERT INTO prediction
                  (statement, node_id, probability, issued_on, due_on,
                   params_version, cohort_id, node_code, pillar, target_level,
                   horizon_months, raw_probability, calibrated_probability,
                   calibration_version, information_status, input_sha256,
                   statement_sha256, node_set_version, rubric_version)
                SELECT name_ko || ' advances', id, 0.40, DATE '2026-07-16',
                       DATE '2027-01-16', 'hazard-v1', :cohortId, code, pillar,
                       current_level + 1, 6, 0.40, 0.40,
                       :calibrationVersion, 'INFORMATIVE', :inputHash,
                       :statementHash, node_set_version, 'r2.0'
                  FROM capability_node WHERE code = :nodeCode
                """)
                .param("cohortId", cohortId)
                .param("calibrationVersion", calibrationVersion)
                .param("inputHash", inputHash)
                .param("statementHash", inputHash)
                .param("nodeCode", nodeCode)
                .update();
        return jdbc.sql("""
                SELECT id FROM prediction
                 WHERE cohort_id = :cohortId AND node_code = :nodeCode
                """).param("cohortId", cohortId).param("nodeCode", nodeCode)
                .query(Long.class).single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
