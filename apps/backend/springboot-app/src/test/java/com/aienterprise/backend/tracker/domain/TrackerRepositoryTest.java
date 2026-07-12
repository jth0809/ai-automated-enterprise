package com.aienterprise.backend.tracker.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    @Test
    void pendingExtractionsAreOldestFirstWithBodyDomainAllowlist() {
        long sourceId = id("source_registry", "code", "NASA");
        long first = repository.insertArticleIfNew(
                "https://www.nasa.gov/one", "d".repeat(64), sourceId,
                "One", Instant.parse("2026-07-13T00:00:00Z"), "Body", "PENDING").orElseThrow();
        long second = repository.insertArticleIfNew(
                "https://www.nasa.gov/two", "e".repeat(64), sourceId,
                "Two", Instant.parse("2026-07-13T01:00:00Z"), "Body", "PENDING").orElseThrow();

        var candidates = repository.findPendingExtractions(10);

        assertEquals(List.of(first, second),
                candidates.stream().map(ExtractionCandidate::id).toList());
        assertTrue(candidates.get(0).allowedHosts().contains("www.nasa.gov"));
        assertTrue(candidates.get(0).allowedHosts().contains("science.nasa.gov"),
                "BODY-purpose domains must be part of the fetch allowlist");
    }

    @Test
    void verifiedEvidenceBodiesAreProjectedForTheFlukeFilter() {
        long sourceId = id("source_registry", "code", "NASA");
        long nodeId = id("capability_node", "code", "P1-ORBIT-REFUEL");
        long rubricId = id("rubric_version", "version_label", "r1.0");
        long articleId = repository.insertArticleIfNew(
                "https://example.test/fluke", "9".repeat(64), sourceId,
                "Fluke source", Instant.parse("2026-01-01T00:00:00Z"),
                "Body text for the fluke filter.").orElseThrow();
        long verifiedId = repository.insertClassification(new ClassificationRow(
                0, articleId, null, "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6, "SpaceX",
                LocalDate.of(2026, 1, 30), "THIRD_PARTY", "a quote", true, "{}", rubricId));
        long unverifiedId = repository.insertClassification(new ClassificationRow(
                0, articleId, null, "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6, "SpaceX",
                LocalDate.of(2026, 1, 30), "THIRD_PARTY", "bad quote", false, "{}", rubricId));
        long eventId = repository.upsertEventByNaturalKey(
                "P1-ORBIT-REFUEL|FLIGHT_TEST|fluke|2926",
                EventRow.draft(nodeId, "FLIGHT_TEST", 6, "SpaceX", LocalDate.of(2026, 1, 30),
                        "CLAIMED", LocalDate.of(2026, 4, 30), rubricId));
        repository.linkClassification(verifiedId, eventId);
        repository.linkClassification(unverifiedId, eventId);

        assertEquals(List.of("Body text for the fluke filter."),
                repository.findVerifiedEvidenceBodies(eventId));
    }

    @Test
    void reviewMapperPreservesPriorityAndFlukeState() {
        long nodeId = id("capability_node", "code", "P1-ORBIT-REFUEL");
        long rubricId = id("rubric_version", "version_label", "r1.0");
        long eventId = repository.upsertEventByNaturalKey(
                "P1-ORBIT-REFUEL|FLIGHT_TEST|mapper|2926",
                EventRow.draft(nodeId, "FLIGHT_TEST", 7, "SpaceX", LocalDate.of(2026, 1, 30),
                        "OFFICIAL", LocalDate.of(2026, 4, 30), rubricId));
        long reviewId = repository.insertReview(eventId, "HIGH_IMPACT");

        var review = repository.findReviewById(reviewId).orElseThrow();

        assertEquals(0, review.priority());
        assertEquals("PENDING", review.flukeStatus());
        assertEquals(0, review.flukeFailCount());
    }

    @Test
    void activeRubricVersionIdReturnsTheSeededActiveRow() {
        assertEquals(id("rubric_version", "version_label", "r1.0"), repository.activeRubricVersionId());
    }

    @Test
    void nodeCodeExistenceChecksTheRegistry() {
        assertTrue(repository.nodeCodeExists("P1-ORBIT-REFUEL"));
        assertFalse(repository.nodeCodeExists("P9-NOT-REGISTERED"));
    }

    @Test
    void insertedClassificationRoundTripsWithQuoteFlag() {
        long sourceId = id("source_registry", "code", "NASA");
        long rubricId = id("rubric_version", "version_label", "r1.0");
        long articleId = repository.insertArticleIfNew(
                "https://example.test/classified", "b".repeat(64), sourceId,
                "A title", Instant.parse("2026-01-01T00:00:00Z"), "Body").orElseThrow();

        repository.insertClassification(new ClassificationRow(
                0, articleId, null, "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6, "SpaceX",
                LocalDate.of(2026, 7, 1), "THIRD_PARTY", "a quote", false, "{}", rubricId));

        assertEquals("N", jdbc.sql("SELECT quote_verified FROM article_classification WHERE article_id = :id")
                .param("id", articleId).query(String.class).single());
    }

    @Test
    void recentNaturalKeysAreReturnedNewestFirst() {
        long nodeId = id("capability_node", "code", "P1-ORBIT-REFUEL");
        long rubricId = id("rubric_version", "version_label", "r1.0");
        EventRow draft = EventRow.draft(
                nodeId, "FLIGHT_TEST", 7, "Example Corp", LocalDate.of(2026, 2, 1),
                "CLAIMED", LocalDate.of(2026, 5, 2), rubricId);
        repository.upsertEventByNaturalKey("P1-ORBIT-REFUEL|FLIGHT_TEST|older|2900", draft);
        repository.upsertEventByNaturalKey("P1-ORBIT-REFUEL|FLIGHT_TEST|newer|2901", draft);

        var keys = repository.findRecentNaturalKeys(20);

        assertEquals(List.of(
                "P1-ORBIT-REFUEL|FLIGHT_TEST|newer|2901",
                "P1-ORBIT-REFUEL|FLIGHT_TEST|older|2900"), keys);
    }

    private long id(String table, String column, String value) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE " + column + " = :value")
                .param("value", value).query(Long.class).single();
    }
}
