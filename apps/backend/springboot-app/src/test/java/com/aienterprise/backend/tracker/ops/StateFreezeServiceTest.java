package com.aienterprise.backend.tracker.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ops.StateFreezeService.Trigger;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"tracker.enabled=true", "tracker.backfill-on-boot=false"})
@ActiveProfiles("test")
@Transactional
class StateFreezeServiceTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private StateFreezeService service;

    @Test
    void freezeIsIdempotentAndWritesOneConsistentAuditTransition() {
        assertFalse(service.isFrozen());

        assertTrue(service.freeze("golden agreement below 0.90", Trigger.DRILL));
        assertFalse(service.freeze("duplicate trigger", Trigger.DRILL));

        assertTrue(service.isFrozen());
        assertEquals("golden agreement below 0.90",
                repository.findOpsState(StateFreezeService.FREEZE_REASON_KEY).orElseThrow().value());
        assertEquals("DRILL",
                repository.findOpsState(StateFreezeService.FREEZE_TRIGGER_KEY).orElseThrow().value());
        assertTrue(repository.findOpsState(StateFreezeService.FREEZE_AT_KEY).isPresent());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM ops_action_log")
                .query(Integer.class).single());
        assertEquals("ACTIVE|FROZEN|DRILL", jdbc.sql("""
                SELECT previous_state || '|' || new_state || '|' || trigger_type
                  FROM ops_action_log
                """).query(String.class).single());
    }

    @Test
    void releaseRequiresHumanTriggerAndBoundedNonblankReason() {
        service.freeze("test freeze", Trigger.AUTOMATIC);

        assertThrows(IllegalArgumentException.class,
                () -> service.release("   ", Trigger.HUMAN));
        assertThrows(IllegalArgumentException.class,
                () -> service.release("automatic release", Trigger.AUTOMATIC));
        assertThrows(IllegalArgumentException.class,
                () -> service.release("x".repeat(2_001), Trigger.HUMAN));
        assertTrue(service.isFrozen());

        assertTrue(service.release("r".repeat(1_500), Trigger.HUMAN));
        assertFalse(service.release("duplicate release", Trigger.HUMAN));

        assertFalse(service.isFrozen());
        assertTrue(repository.findOpsState(StateFreezeService.FREEZE_REASON_KEY).isEmpty());
        assertTrue(repository.findOpsState(StateFreezeService.FREEZE_TRIGGER_KEY).isEmpty());
        assertTrue(repository.findOpsState(StateFreezeService.FREEZE_AT_KEY).isEmpty());
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM ops_action_log")
                .query(Integer.class).single());
        assertEquals(1_500, jdbc.sql("""
                SELECT LENGTH(reason) FROM ops_action_log
                 WHERE action_type = 'RELEASE'
                """).query(Integer.class).single());
        assertEquals("FROZEN|ACTIVE|HUMAN", jdbc.sql("""
                SELECT previous_state || '|' || new_state || '|' || trigger_type
                  FROM ops_action_log
                 WHERE action_type = 'RELEASE'
                """).query(String.class).single());
    }
}
