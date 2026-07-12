package com.aienterprise.backend.tracker.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class StateUpdaterTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private StateUpdater updater;

    @Test
    void officialSingleStepAdvanceConfirmsEventAndWritesHistory() {
        setLevel("P1-REUSE-LV", 6);
        long eventId = event("P1-REUSE-LV", "OPERATIONAL_DEPLOYMENT", 7, "OFFICIAL", "adv");

        updater.processPending();

        assertEquals(7, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
        assertEquals("CONFIRMED", eventField(eventId, "event_status"));
        assertEquals("Y", eventField(eventId, "state_advanced"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM node_state_history WHERE cause_event_id = :id")
                .param("id", eventId).query(Integer.class).single());
    }

    @Test
    void levelEightClaimIsHeldForHumanReview() {
        setLevel("P1-REUSE-LV", 7);
        long eventId = event("P1-REUSE-LV", "OPERATIONAL_DEPLOYMENT", 8, "OFFICIAL", "rev");

        updater.processPending();

        assertEquals(7, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
        assertEquals("PROVISIONAL", eventField(eventId, "event_status"));
        assertEquals("HIGH_IMPACT", jdbc.sql(
                "SELECT reason FROM review_queue WHERE event_id = :id AND status = 'PENDING'")
                .param("id", eventId).query(String.class).single());

        // A second pass must not enqueue a duplicate review.
        updater.processPending();
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM review_queue WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single());
    }

    @Test
    void officialRollbackLowersTheNodeLevel() {
        setLevel("P6-GOV-FRAMEWORK", 5);
        long eventId = event("P6-GOV-FRAMEWORK", "ROLLBACK", 4, "OFFICIAL", "rb");

        updater.processPending();

        assertEquals(4, repository.findNodeByCode("P6-GOV-FRAMEWORK").currentLevel());
        assertEquals("CONFIRMED", eventField(eventId, "event_status"));
    }

    private void setLevel(String nodeCode, int level) {
        jdbc.sql("UPDATE capability_node SET current_level = :level WHERE code = :code")
                .param("level", level).param("code", nodeCode).update();
    }

    private long event(String nodeCode, String eventType, int claimedLevel, String verification, String key) {
        long nodeId = jdbc.sql("SELECT id FROM capability_node WHERE code = :code")
                .param("code", nodeCode).query(Long.class).single();
        return repository.upsertEventByNaturalKey(
                nodeCode + "|" + eventType + "|" + key + "|2926",
                EventRow.draft(nodeId, eventType, claimedLevel, "SpaceX", LocalDate.of(2026, 1, 30),
                        verification, LocalDate.of(2026, 4, 30), repository.activeRubricVersionId()));
    }

    private String eventField(long eventId, String column) {
        return jdbc.sql("SELECT " + column + " FROM event WHERE id = :id")
                .param("id", eventId).query(String.class).single();
    }
}
