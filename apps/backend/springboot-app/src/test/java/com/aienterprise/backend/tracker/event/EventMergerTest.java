package com.aienterprise.backend.tracker.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.ClassificationRow;
import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class EventMergerTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private EventMerger merger;

    @Test
    void sequentialMergesFromThreeSourcesEscalateVerification() {
        // Rubric §5 ordering: CLAIMED < PEER_REVIEWED < OFFICIAL < INDEPENDENT,
        // highest applicable wins — so once two distinct Tier 1~2 sources exist
        // the cluster stays INDEPENDENT even after the agency source joins.
        ClassificationRow spaceNews = classified("SPACENEWS", "1", "THIRD_PARTY", LocalDate.of(2026, 1, 30));
        ClassificationRow nsf = classified("NASASPACEFLIGHT", "2", "THIRD_PARTY", LocalDate.of(2026, 1, 31));
        ClassificationRow nasa = classified("NASA", "3", "PRIMARY", LocalDate.of(2026, 2, 1));

        long first = merger.mergeClaim(spaceNews);
        assertEquals("CLAIMED", verification(first));
        assertEquals("P1-ORBIT-REFUEL|FLIGHT_TEST|spacex|2926", naturalKey(first));

        long second = merger.mergeClaim(nsf);
        assertEquals(first, second);
        assertEquals("INDEPENDENT", verification(first));

        long third = merger.mergeClaim(nasa);
        assertEquals(first, third);
        assertEquals("INDEPENDENT", verification(first));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM event").query(Integer.class).single());
    }

    @Test
    void claimsFiveDaysApartInTheSameWeekBucketMerge() {
        ClassificationRow early = classified("SPACENEWS", "4", "THIRD_PARTY", LocalDate.of(2026, 1, 30));
        ClassificationRow late = classified("NASA", "5", "PRIMARY", LocalDate.of(2026, 2, 4));

        long first = merger.mergeClaim(early);
        long second = merger.mergeClaim(late);

        assertEquals(first, second);
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM event").query(Integer.class).single());
    }

    @Test
    void runOnceMergesOnlyVerifiedUnlinkedClassifications() {
        ClassificationRow verified = classified("SPACENEWS", "6", "THIRD_PARTY", LocalDate.of(2026, 1, 30));
        long unverifiedId = insertClassification("NASA", "7", "THIRD_PARTY", LocalDate.of(2026, 1, 30), false);

        merger.runOnce();

        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM event").query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM article_classification WHERE id = :id AND event_id IS NOT NULL")
                .param("id", verified.id()).query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM article_classification WHERE id = :id AND event_id IS NULL")
                .param("id", unverifiedId).query(Integer.class).single());
    }

    private static final String SUBSTANTIVE_QUOTE =
            "Falcon booster completed a full static fire test";

    @Test
    void safeSemanticMatchLinksAcrossDifferentNaturalKeys() {
        LocalDate when = LocalDate.of(2026, 1, 30);
        ClassificationRow first = claim("SPACENEWS", "sa", when, "SpaceX", SUBSTANTIVE_QUOTE);
        ClassificationRow second = claim("NASA", "sb", when, "SpaceX Alliance", SUBSTANTIVE_QUOTE);

        long firstEvent = merger.mergeClaim(first);
        long secondEvent = merger.mergeClaim(second);

        assertEquals(firstEvent, secondEvent);
        assertEquals(1, eventCount());
    }

    @Test
    void exactNaturalKeyWinsRegardlessOfQuote() {
        LocalDate when = LocalDate.of(2026, 1, 30);
        ClassificationRow first = claim("SPACENEWS", "xa", when, "SpaceX", SUBSTANTIVE_QUOTE);
        ClassificationRow second = claim("NASA", "xb", when, "SpaceX",
                "Unrelated note about garden tomatoes growing in summer");

        long firstEvent = merger.mergeClaim(first);
        long secondEvent = merger.mergeClaim(second);

        assertEquals(firstEvent, secondEvent);
        assertEquals(1, eventCount());
    }

    @Test
    void ambiguousSemanticMatchCreatesANewEvent() {
        LocalDate when = LocalDate.of(2026, 1, 30);
        long firstEvent = merger.mergeClaim(
                claim("SPACENEWS", "aa", when, "Alpha Team", SUBSTANTIVE_QUOTE));
        long secondEvent = insertEventWithQuote("Beta Team", when, SUBSTANTIVE_QUOTE);

        long thirdEvent = merger.mergeClaim(
                claim("NASA", "ac", when, "Gamma Team", SUBSTANTIVE_QUOTE));

        assertNotEquals(firstEvent, thirdEvent);
        assertNotEquals(secondEvent, thirdEvent);
        assertEquals(3, eventCount());
    }

    @Test
    void reRunningTheSameClaimIsIdempotent() {
        LocalDate when = LocalDate.of(2026, 1, 30);
        ClassificationRow claim = claim("SPACENEWS", "ia", when, "SpaceX", SUBSTANTIVE_QUOTE);

        long firstEvent = merger.mergeClaim(claim);
        long secondEvent = merger.mergeClaim(claim);

        assertEquals(firstEvent, secondEvent);
        assertEquals(1, eventCount());
        assertEquals(1, jdbc.sql(
                "SELECT COUNT(*) FROM article_classification WHERE id = :id AND event_id = :eventId")
                .param("id", claim.id()).param("eventId", firstEvent)
                .query(Integer.class).single());
    }

    private int eventCount() {
        return jdbc.sql("SELECT COUNT(*) FROM event").query(Integer.class).single();
    }

    private ClassificationRow claim(
            String sourceCode, String discriminator, LocalDate occurredOn, String actor, String quote) {
        long id = insertClassificationRow(sourceCode, discriminator, occurredOn, actor, quote, true);
        long articleId = jdbc.sql("SELECT article_id FROM article_classification WHERE id = :id")
                .param("id", id).query(Long.class).single();
        return new ClassificationRow(id, articleId, null, "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6,
                actor, occurredOn, "THIRD_PARTY", quote, true, "{}", repository.activeRubricVersionId());
    }

    private long insertClassificationRow(
            String sourceCode, String discriminator, LocalDate occurredOn,
            String actor, String quote, boolean quoteVerified) {
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = :code")
                .param("code", sourceCode).query(Long.class).single();
        long articleId = repository.insertArticleIfNew(
                "https://example.test/" + sourceCode + "/" + discriminator,
                discriminator.repeat(64).substring(0, 64), sourceId,
                "A title", Instant.parse("2026-02-01T00:00:00Z"), "Body").orElseThrow();
        return repository.insertClassification(new ClassificationRow(
                0, articleId, null, "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6, actor,
                occurredOn, "THIRD_PARTY", quote, quoteVerified, "{}",
                repository.activeRubricVersionId()));
    }

    /** Creates a distinct event directly (bypassing the merger) with a verified quote. */
    private long insertEventWithQuote(String actor, LocalDate occurredOn, String quote) {
        NodeRow node = repository.findNodeByCode("P1-ORBIT-REFUEL");
        long rubricId = repository.activeRubricVersionId();
        String naturalKey = EventMerger.naturalKey("P1-ORBIT-REFUEL", "FLIGHT_TEST", actor, occurredOn);
        long eventId = repository.upsertEventByNaturalKey(naturalKey, EventRow.draft(
                node.id(), "FLIGHT_TEST", 6, actor, occurredOn, "CLAIMED",
                occurredOn.plusDays(90), rubricId));
        long classificationId = insertClassificationRow(
                "NASASPACEFLIGHT", "seed-" + actor.replaceAll("\\s", ""),
                occurredOn, actor, quote, true);
        repository.linkClassification(classificationId, eventId);
        return eventId;
    }

    private ClassificationRow classified(String sourceCode, String discriminator, String path, LocalDate occurredOn) {
        long id = insertClassification(sourceCode, discriminator, path, occurredOn, true);
        long articleId = jdbc.sql("SELECT article_id FROM article_classification WHERE id = :id")
                .param("id", id).query(Long.class).single();
        long rubricId = repository.activeRubricVersionId();
        return new ClassificationRow(id, articleId, null, "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6,
                "SpaceX", occurredOn, path, "a quote", true, "{}", rubricId);
    }

    private long insertClassification(
            String sourceCode, String discriminator, String path, LocalDate occurredOn, boolean quoteVerified) {
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = :code")
                .param("code", sourceCode).query(Long.class).single();
        long articleId = repository.insertArticleIfNew(
                "https://example.test/" + sourceCode + "/" + discriminator,
                discriminator.repeat(64).substring(0, 64), sourceId,
                "A title", Instant.parse("2026-02-01T00:00:00Z"), "Body").orElseThrow();
        return repository.insertClassification(new ClassificationRow(
                0, articleId, null, "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6, "SpaceX",
                occurredOn, path, "a quote", quoteVerified, "{}", repository.activeRubricVersionId()));
    }

    private String verification(long eventId) {
        return jdbc.sql("SELECT verification_level FROM event WHERE id = :id")
                .param("id", eventId).query(String.class).single();
    }

    private String naturalKey(long eventId) {
        return jdbc.sql("SELECT natural_key FROM event WHERE id = :id")
                .param("id", eventId).query(String.class).single();
    }
}
