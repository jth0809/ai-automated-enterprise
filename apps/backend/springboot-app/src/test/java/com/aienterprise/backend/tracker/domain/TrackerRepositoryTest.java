package com.aienterprise.backend.tracker.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TrackerRepositoryTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Test
    void duplicateArticleUrlHashIsAnIdempotentNoOp() {
        long sourceId = id("source_registry", "code", "NASA");

        var first = repository.insertArticleIfNew(
                "https://example.test/article", "a".repeat(64), sourceId,
                "A title", Instant.parse("2026-01-01T00:00:00Z"), "Body");
        var second = repository.insertArticleIfNew(
                "https://example.test/article", "a".repeat(64), sourceId,
                "A duplicate", Instant.parse("2026-01-01T00:00:00Z"), "Other body");

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM article WHERE url_hash = :hash")
                .param("hash", "a".repeat(64)).query(Integer.class).single());
    }

    @Test
    void eventUpsertReturnsExistingIdForTheSameNaturalKey() {
        long nodeId = id("capability_node", "code", "P1-ORBIT-REFUEL");
        long rubricId = id("rubric_version", "version_label", "r1.0");
        EventRow draft = EventRow.draft(
                nodeId, "FLIGHT_TEST", 7, "Example Corp", LocalDate.of(2026, 2, 1),
                "CLAIMED", LocalDate.of(2026, 5, 2), rubricId);

        long first = repository.upsertEventByNaturalKey("P1-ORBIT-REFUEL|FLIGHT_TEST|examplecorp|2926", draft);
        long second = repository.upsertEventByNaturalKey("P1-ORBIT-REFUEL|FLIGHT_TEST|examplecorp|2926", draft);

        assertEquals(first, second);
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM event WHERE natural_key = :key")
                .param("key", "P1-ORBIT-REFUEL|FLIGHT_TEST|examplecorp|2926")
                .query(Integer.class).single());
    }

    @Test
    void advancingNodeUpdatesCurrentStateAndAppendsHistoryAtomically() {
        long nodeId = id("capability_node", "code", "P1-ORBIT-REFUEL");
        long rubricId = id("rubric_version", "version_label", "r1.0");
        EventRow draft = EventRow.draft(
                nodeId, "FLIGHT_TEST", 7, "Example Corp", LocalDate.of(2026, 2, 1),
                "OFFICIAL", LocalDate.of(2026, 5, 2), rubricId);
        long eventId = repository.upsertEventByNaturalKey(
                "P1-ORBIT-REFUEL|FLIGHT_TEST|examplecorp|2926", draft);

        repository.advanceNode(nodeId, 7, "OFFICIAL", eventId, rubricId);

        assertEquals(7, repository.findNodeByCode("P1-ORBIT-REFUEL").currentLevel());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM node_state_history WHERE cause_event_id = :eventId")
                .param("eventId", eventId).query(Integer.class).single());
    }

    private long id(String table, String column, String value) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE " + column + " = :value")
                .param("value", value).query(Long.class).single();
    }
}
