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
class TrackerPhase4V17SchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void activatesParamsV2AndVersionsExplicitUncertainty() {
        assertEquals(1, count("""
                SELECT COUNT(*) FROM parameter_set
                 WHERE version_label = 'params-v2'
                   AND active = 'Y'
                   AND epsilon = 0.010
                   AND k_shrink = 4
                   AND window_m = 6
                   AND window_fixed_years = 10
                   AND window_min_years = 4
                   AND window_max_years = 15
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM parameter_set
                 WHERE version_label = 'params-v1' AND active = 'N'
                """));
        assertEquals(9, count("""
                SELECT COUNT(*) FROM parameter_uncertainty u
                  JOIN parameter_set p ON p.id = u.parameter_set_id
                 WHERE p.version_label = 'params-v2'
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM parameter_uncertainty u
                  JOIN parameter_set p ON p.id = u.parameter_set_id
                 WHERE p.version_label = 'params-v2'
                   AND u.parameter_name = 'delta_scale'
                   AND u.distribution_type = 'DISCRETE'
                   AND u.lower_value = 0.75
                   AND u.central_value = 1.00
                   AND u.upper_value = 1.25
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM parameter_uncertainty u
                  JOIN parameter_set p ON p.id = u.parameter_set_id
                 WHERE p.version_label = 'params-v2'
                   AND u.parameter_name = 'mc_samples'
                   AND u.lower_value = 1000
                   AND u.central_value = 4000
                   AND u.upper_value = 10000
                """));
    }

    @Test
    void createsEmptyHumanReviewedRegimeRegistryWithoutAutomatic2010Break() {
        assertEquals(0, count("SELECT COUNT(*) FROM model_regime_break"));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM model_regime_break
                 WHERE break_date = DATE '2010-01-01'
                """));
    }

    @Test
    void createsProjectionRunAndSevenResultSlotsWithoutPublishingPartialRuns() {
        jdbc.sql("""
                INSERT INTO projection_run
                  (input_sha256, seed_value, sample_count, params_version,
                   graph_version, node_set_version, dataset_sha256, run_status,
                   invalid_sample_count, current_result)
                VALUES
                  (:inputHash, 42, 4000, 'params-v2',
                   'graph-v1.0', 'nodes-v1.0', :datasetHash, 'RUNNING', 0, 'N')
                """)
                .param("inputHash", "a".repeat(64))
                .param("datasetHash", "b".repeat(64))
                .update();
        long runId = jdbc.sql("""
                SELECT id FROM projection_run WHERE input_sha256 = :inputHash
                """)
                .param("inputHash", "a".repeat(64))
                .query(Long.class)
                .single();

        for (int pillar = 0; pillar <= 6; pillar++) {
            jdbc.sql("""
                    INSERT INTO projection_result
                      (run_id, pillar, readiness, eta_p10, eta_p50, eta_p90,
                       censored_fraction, momentum)
                    VALUES
                      (:runId, :pillar, 0.25, 2040, 2050, NULL,
                       0.20, 'STEADY')
                    """)
                    .param("runId", runId)
                    .param("pillar", pillar)
                    .update();
        }

        assertEquals(7, count("SELECT COUNT(*) FROM projection_result"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE projection_run
                   SET current_result = 'Y'
                 WHERE id = :runId AND run_status = 'RUNNING'
                """).param("runId", runId).update());
    }

    @Test
    void rejectsInvalidUncertaintyAndProjectionBounds() {
        long paramsId = jdbc.sql("""
                SELECT id FROM parameter_set WHERE version_label = 'params-v2'
                """).query(Long.class).single();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO parameter_uncertainty
                  (parameter_set_id, parameter_name, distribution_type,
                   lower_value, central_value, upper_value, scale_value)
                VALUES
                  (:paramsId, 'invalid-order', 'FIXED', 2, 1, 3, NULL)
                """).param("paramsId", paramsId).update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO projection_run
                  (input_sha256, seed_value, sample_count, params_version,
                   graph_version, node_set_version, dataset_sha256, run_status,
                   invalid_sample_count, current_result)
                VALUES
                  (:inputHash, 42, 999, 'params-v2',
                   'graph-v1.0', 'nodes-v1.0', :datasetHash, 'RUNNING', 0, 'N')
                """)
                .param("inputHash", "c".repeat(64))
                .param("datasetHash", "d".repeat(64))
                .update());
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
