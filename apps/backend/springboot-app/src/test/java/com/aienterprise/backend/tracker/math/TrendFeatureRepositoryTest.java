package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TrendFeatureRepositoryTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrendFeatureRepository repository;

    @Test
    void stateChangesAndBreaksAreStrictlyBoundedByAsOfDate() {
        long earlier = insertChange("trend-earlier", "INSTITUTIONAL_ADVANCE",
                LocalDate.of(2020, 1, 1), 1, 2);
        insertChange("trend-future", "ROLLBACK",
                LocalDate.of(2030, 1, 1), 2, 1);
        jdbc.sql("""
                INSERT INTO model_regime_break
                  (pillar, break_date, cause_event_id, review_status,
                   reviewer, reviewer_note, params_version)
                VALUES
                  (6, DATE '2020-01-01', :eventId, 'APPROVED',
                   'test-reviewer', 'Approved synthetic break.', 'params-v2')
                """).param("eventId", earlier).update();

        LocalDate cutoff = LocalDate.of(2025, 1, 1);
        var changes = repository.findStateChanges(6, cutoff);
        var regime = repository.findLatestApprovedBreak(6, cutoff, "params-v2");

        assertEquals(1, changes.size());
        assertEquals(LocalDate.of(2020, 1, 1), changes.getFirst().occurredOn());
        assertTrue(regime.isPresent());
        assertEquals(LocalDate.of(2020, 1, 1), regime.orElseThrow().breakDate());
    }

    @Test
    void unconfirmedEventsAndNoOpHistoryAreNotTrendEvents() {
        long eventId = insertEvent(
                "trend-provisional", "FLIGHT_TEST", LocalDate.of(2020, 1, 1),
                "PROVISIONAL");
        insertHistory(eventId, 2, 2, "ACTIVE", "ACTIVE");

        assertTrue(repository.findStateChanges(
                6, LocalDate.of(2025, 1, 1)).isEmpty());
    }

    private long insertChange(
            String key, String type, LocalDate occurredOn, int previous, int next) {
        long eventId = insertEvent(key, type, occurredOn, "CONFIRMED");
        insertHistory(eventId, previous, next, "ACTIVE", "ACTIVE");
        return eventId;
    }

    private long insertEvent(
            String key, String type, LocalDate occurredOn, String status) {
        jdbc.sql("""
                INSERT INTO event
                  (natural_key, node_id, event_type, claimed_level, actor, occurred_on,
                   verification_level, event_status, state_advanced, rubric_version_id)
                VALUES
                  (:key, (SELECT id FROM capability_node WHERE code = 'P6-FUNDING'),
                   :type, 2, 'Trend fixture', :occurredOn,
                   'OFFICIAL', :status, 'Y',
                   (SELECT id FROM rubric_version WHERE version_label = 'r2.0'))
                """)
                .param("key", key)
                .param("type", type)
                .param("occurredOn", java.sql.Date.valueOf(occurredOn))
                .param("status", status)
                .update();
        return jdbc.sql("SELECT id FROM event WHERE natural_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private void insertHistory(
            long eventId, int previous, int next,
            String previousStatus, String nextStatus) {
        jdbc.sql("""
                INSERT INTO node_state_history
                  (node_id, prev_level, new_level, prev_status, new_status,
                   verification_level, cause_event_id, rubric_version_id)
                VALUES
                  ((SELECT id FROM capability_node WHERE code = 'P6-FUNDING'),
                   :previous, :next, :previousStatus, :nextStatus,
                   'OFFICIAL', :eventId,
                   (SELECT id FROM rubric_version WHERE version_label = 'r2.0'))
                """)
                .param("previous", previous)
                .param("next", next)
                .param("previousStatus", previousStatus)
                .param("nextStatus", nextStatus)
                .param("eventId", eventId)
                .update();
    }
}
