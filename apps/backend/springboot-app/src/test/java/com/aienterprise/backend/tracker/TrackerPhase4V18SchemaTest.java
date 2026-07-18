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
class TrackerPhase4V18SchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void storesVersionedBacktestRunFoldsAndMetrics() {
        long runId = insertRunning("a".repeat(64));

        jdbc.sql("""
                INSERT INTO backtest_fold
                  (run_id, fold_index, cohort, cutoff_date, target_date, pillar,
                   current_readiness, predicted_readiness, actual_readiness,
                   predicted_logit, actual_logit, predicted_advance,
                   actual_advance, interval_p10, interval_p90, covered,
                   eta_year, fold_status)
                VALUES
                  (:runId, 0, 'CALIBRATION', DATE '2008-01-07',
                   DATE '2009-01-05', 1, 0.20, 0.24, 0.22,
                   -1.15, -1.27, 'Y', 'Y', 0.18, 0.29, 'Y',
                   2075.2, 'OK')
                """).param("runId", runId).update();
        jdbc.sql("""
                INSERT INTO backtest_metric
                  (run_id, metric_code, pillar,
                   calibration_value, holdout_value,
                   calibration_samples, holdout_samples,
                   calibration_status, holdout_status)
                VALUES
                  (:runId, 'READINESS_MAE', 1, 0.02, 0.03,
                   52, 16, 'OK', 'OK')
                """).param("runId", runId).update();

        assertEquals(1, count("SELECT COUNT(*) FROM backtest_run"));
        assertEquals(1, count("SELECT COUNT(*) FROM backtest_fold"));
        assertEquals(1, count("SELECT COUNT(*) FROM backtest_metric"));
    }

    @Test
    void preventsPublishingRunningOrMalformedBacktests() {
        long runId = insertRunning("b".repeat(64));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE backtest_run SET current_result = 'Y' WHERE id = :runId
                """).param("runId", runId).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO backtest_fold
                  (run_id, fold_index, cohort, cutoff_date, target_date, pillar,
                   fold_status)
                VALUES
                  (:runId, 0, 'LEAKED', DATE '2009-01-05',
                   DATE '2010-01-04', 1, 'INSUFFICIENT_DATA')
                """).param("runId", runId).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO backtest_metric
                  (run_id, metric_code, pillar, calibration_samples,
                   holdout_samples, calibration_status, holdout_status)
                VALUES
                  (:runId, 'BRIER', 0, 0, 0,
                   'INSUFFICIENT_DATA', 'INSUFFICIENT_DATA')
                """).param("runId", runId).update());
    }

    @Test
    void completedRunRequiresReportAndValidPredeclaredCandidate() {
        long runId = insertRunning("c".repeat(64));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE backtest_run
                   SET run_status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                 WHERE id = :runId
                """).param("runId", runId).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE backtest_run SET selected_delta_scale = 1.10
                 WHERE id = :runId
                """).param("runId", runId).update());
    }

    private long insertRunning(String inputHash) {
        jdbc.sql("""
                INSERT INTO backtest_run
                  (input_sha256, dataset_sha256, node_set_version,
                   rubric_version, params_version, graph_version,
                   candidate_registry_version, calibration_start,
                   calibration_end, holdout_start, holdout_end,
                   horizon_weeks, sample_count, selected_window_m,
                   selected_k_shrink, selected_delta_scale,
                   objective_score, run_status, current_result)
                VALUES
                  (:inputHash, :datasetHash, 'nodes-v1.0', 'r2.0',
                   'params-v2', 'graph-v1.0', 'backtest-candidates-v1',
                   DATE '1957-01-07', DATE '2009-12-31',
                   DATE '2010-01-01', DATE '2026-07-13',
                   52, 1000, 6, 4, 1.00, 0.25, 'RUNNING', 'N')
                """)
                .param("inputHash", inputHash)
                .param("datasetHash", "d".repeat(64))
                .update();
        return jdbc.sql("""
                SELECT id FROM backtest_run WHERE input_sha256 = :inputHash
                """).param("inputHash", inputHash).query(Long.class).single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
