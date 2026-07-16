package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

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
class TrackerForecastV15SchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void createsReviewedReferencesAndIdentifiedObservationColumns() {
        Set<String> columns = Set.copyOf(jdbc.sql("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = 'EXTERNAL_FORECAST'
                """).query(String.class).list());

        assertTrue(columns.contains("FORECAST_KEY"));
        assertTrue(columns.contains("OBSERVATION_SHA256"));
        assertTrue(columns.contains("OBSERVATION_STATUS"));
        assertTrue(columns.contains("SMOOTHING_WINDOW_DAYS"));
        assertEquals(0, count("forecast_reference"));
        assertEquals(0, count("forecast_reference_import"));
    }

    @Test
    void acceptsTheThreeTracksAndRejectsInventedEnums() {
        insertReference("LANDING", "DIRECT", "NASA-LANDING");
        insertReference("RETURN", "REQUIREMENT", "NASA-RETURN");
        insertReference("SETTLEMENT", "PROXY", "NASA-SETTLEMENT");

        assertEquals(3, count("forecast_reference"));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertReference("UNKNOWN_TRACK", "DIRECT", "BAD-TRACK"));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertReference("LANDING", "INVENTED", "BAD-RELATION"));
    }

    @Test
    void preservesLegacyExternalForecastRowsButConstrainsIdentifiedRows() {
        jdbc.sql("""
                INSERT INTO external_forecast
                  (source_type, source_name, question, forecast_year, retrieved_on)
                VALUES ('CROWD', 'legacy', 'legacy row', 2040.0, DATE '2026-07-15')
                """).update();
        assertEquals(1, count("external_forecast"));

        insertReference("LANDING", "DIRECT", "METACULUS-LANDING");
        insertObservation("METACULUS-LANDING", "a".repeat(64));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertObservation("METACULUS-LANDING", "b".repeat(64)));
    }

    private void insertReference(String track, String relation, String key) {
        jdbc.sql("""
                INSERT INTO forecast_reference
                  (forecast_key, source_type, source_name, track_code, question,
                   target_definition, display_status, relation_kind, source_url,
                   accessed_on, ingestion_mode, content_sha256, fact_summary,
                   dataset_version)
                VALUES (:key, 'INSTITUTIONAL', 'NASA', :track, 'Test question',
                        'A bounded target definition for schema validation.',
                        'UNDATED', :relation,
                        'https://www.nasa.gov/moontomarsarchitecture-strategyandobjectives/',
                        DATE '2026-07-15', 'REVIEWED_REFERENCE', :sha,
                        'Reviewer-authored fact summary with enough context for schema validation.',
                        'forecast-reference-test-v1')
                """)
                .param("key", key)
                .param("track", track)
                .param("relation", relation)
                .param("sha", "1".repeat(64))
                .update();
    }

    private void insertObservation(String key, String hash) {
        jdbc.sql("""
                INSERT INTO external_forecast
                  (source_type, source_name, question, forecast_year, smoothed_year,
                   retrieved_on, forecast_key, observation_sha256,
                   observation_status, smoothing_window_days)
                VALUES ('CROWD', 'METACULUS', 'Landing', 2045.0, 2045.0,
                        DATE '2026-07-15', :key, :hash, 'CURRENT', 90)
                """)
                .param("key", key)
                .param("hash", hash)
                .update();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Integer.class).single();
    }
}
