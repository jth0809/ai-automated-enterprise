package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TrackerTransportV12SchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void createsTheSixBoundedTransportTables() {
        List<String> tables = List.of(
                "transport_economics_assumption",
                "transport_price_observation",
                "transport_economics_projection",
                "transport_coherence_report",
                "transport_coherence_sample",
                "transport_economics_import");

        for (String table : tables) {
            Integer count = jdbc.sql("SELECT COUNT(*) FROM " + table)
                    .query(Integer.class)
                    .single();
            assertEquals(0, count, table);
        }
    }

    @Test
    void assumptionTargetsAndImportCardinalityFailClosed() {
        insertAssumption("transport-assumptions-v1", "200", "500", "100");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertAssumption("bad-easy", "200", "200", "100"));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertAssumption("bad-hard", "200", "500", "200"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO transport_economics_import
                  (dataset_version, dataset_sha256, price_observation_count,
                   annual_launch_record_count, cpi_record_count)
                VALUES ('empty', :hash, 0, 2, 1)
                """).param("hash", "a".repeat(64)).update());
    }

    @Test
    void projectionAndCoherenceEnumsRejectInventedStates() {
        insertAssumption("transport-assumptions-v1", "200", "500", "100");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertProjection("INVENTED"));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCoherenceReport("INVENTED"));
    }

    private void insertAssumption(
            String version, String central, String easy, String hard) {
        jdbc.sql("""
                INSERT INTO transport_economics_assumption
                  (assumption_version, model_version, central_target_usd_per_kg,
                   easy_target_usd_per_kg, hard_target_usd_per_kg,
                   price_basis_year, horizon_years, weak_fit_r2, widening_factor)
                VALUES (:version, 'wright-falcon-v1', :central, :easy, :hard,
                        2025, 150, 0.50, 1.50)
                """)
                .param("version", version)
                .param("central", central)
                .param("easy", easy)
                .param("hard", hard)
                .update();
    }

    private void insertProjection(String status) {
        jdbc.sql("""
                INSERT INTO transport_economics_projection
                  (as_of_date, assumption_version, model_version, status,
                   sufficiency_tier, qualification_flags, observation_count,
                   current_cumulative_launches, central_target_usd_per_kg,
                   easy_target_usd_per_kg, hard_target_usd_per_kg,
                   central_beyond_horizon, earliest_beyond_horizon,
                   latest_beyond_horizon, price_basis_year, horizon_years,
                   interval_kind, basis, price_meaning, projection_label, reason_code)
                VALUES (DATE '2026-07-15', 'transport-assumptions-v1',
                        'wright-falcon-v1', :status, 'INSUFFICIENT_DATA', '', 0,
                        0, 200, 500, 100, 'N', 'N', 'N', 2025, 150,
                        'ASSUMPTION_SENSITIVITY', 'PUBLISHED_PRICE',
                        'PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD',
                        'Declared-assumption scenario; not provider internal cost',
                        'NO_VALID_FIT')
                """).param("status", status).update();
    }

    private void insertCoherenceReport(String state) {
        jdbc.sql("""
                INSERT INTO transport_coherence_report
                  (report_period_end, price_direction, cadence_direction,
                   layer_b_direction, layer_c_direction, coherence_state,
                   polarity, consecutive_quarter_streak, alert_active,
                   widening_factor)
                VALUES (DATE '2026-06-30', 'FLAT', 'FLAT', 'FLAT', 'FLAT',
                        :state, 'NONE', 0, 'N', 1.00)
                """).param("state", state).update();
    }
}
