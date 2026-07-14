package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.api.TrackerAdminController.Decision;
import com.aienterprise.backend.tracker.domain.EvidenceKind;
import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.HistoricalEvidenceRow;
import com.aienterprise.backend.tracker.domain.ReviewPage;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ops.StateFreezeService;
import com.aienterprise.backend.tracker.ops.StateFreezeService.Trigger;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"tracker.enabled=true", "tracker.admin-token=test-secret"})
@ActiveProfiles("test")
@Transactional
class TrackerAdminControllerTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private TrackerAdminController controller;

    @Autowired
    private StateFreezeService freezeService;

    @Test
    void mismatchedTokenIsUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.reviewQueue("wrong-token").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.decide(1, "wrong-token", new Decision("APPROVE", null)).getStatusCode());
    }

    @Test
    void pendingReviewsAreListedWithAValidToken() {
        long eventId = event("list");
        repository.insertReview(eventId, "LEVEL_JUMP");

        var response = controller.reviewQueue("test-secret");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals(eventId, response.getBody().get(0).eventId());
        assertEquals("PENDING", response.getBody().get(0).flukeStatus());
    }

    @Test
    void formalReviewPageRequiresAuthenticationAndValidFilters() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.reviewPage(null, "PENDING", null, 0, 25).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.reviewPage("wrong-token", "PENDING", null, 0, 25).getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST,
                controller.reviewPage("test-secret", "UNKNOWN", null, 0, 25).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.reviewPage("test-secret", "PENDING", "UNKNOWN", 0, 25).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.reviewPage("test-secret", "PENDING", null, -1, 25).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.reviewPage("test-secret", "PENDING", null, 0, 0).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.reviewPage("test-secret", "PENDING", null, 0, 101).getStatusCode());
    }

    @Test
    void formalReviewPageFiltersStatusAndReasonWithStableTotals() {
        long pendingHigh = repository.insertReview(event("page-pending-high"), "HIGH_IMPACT");
        long pendingLevel = repository.insertReview(event("page-pending-level"), "LEVEL_JUMP");
        long approved = repository.insertReview(event("page-approved"), "HIGH_IMPACT");
        long rejected = repository.insertReview(event("page-rejected"), "FLUKE_MISMATCH");
        repository.resolveReview(approved, "APPROVED", "verified by reviewer");
        repository.resolveReview(rejected, "REJECTED", "not the same event");

        ReviewPage pending = controller.reviewPage(
                "test-secret", "PENDING", "HIGH_IMPACT", 0, 25).getBody();
        assertEquals(1, pending.items().size());
        assertEquals(pendingHigh, pending.items().getFirst().reviewId());
        assertEquals(1, pending.total());
        assertEquals(1, pending.totalPages());
        assertEquals("priority DESC, created_at ASC, id ASC", pending.sort());

        ReviewPage approvedPage = controller.reviewPage(
                "test-secret", "APPROVED", null, 0, 25).getBody();
        assertEquals(1, approvedPage.total());
        assertEquals(approved, approvedPage.items().getFirst().reviewId());
        assertEquals("verified by reviewer", approvedPage.items().getFirst().reviewerNote());
        assertEquals(true, approvedPage.items().getFirst().resolvedAt() != null);

        ReviewPage rejectedPage = controller.reviewPage(
                "test-secret", "REJECTED", null, 0, 25).getBody();
        assertEquals(1, rejectedPage.total());
        assertEquals(rejected, rejectedPage.items().getFirst().reviewId());

        // The reason filter does not leak a row from another pending reason.
        assertEquals(pendingLevel, controller.reviewPage(
                "test-secret", "PENDING", "LEVEL_JUMP", 0, 25)
                .getBody().items().getFirst().reviewId());
    }

    @Test
    void formalReviewPageIsBoundedAndUsesIdAsTheFinalTieBreaker() {
        long first = repository.insertReview(event("page-first"), "HIGH_IMPACT");
        long second = repository.insertReview(event("page-second"), "HIGH_IMPACT");
        jdbc.sql("UPDATE review_queue SET created_at = TIMESTAMP '2026-07-14 00:00:00' "
                + "WHERE id IN (:first, :second)")
                .param("first", first)
                .param("second", second)
                .update();

        ReviewPage firstPage = controller.reviewPage(
                "test-secret", "PENDING", "HIGH_IMPACT", 0, 1).getBody();
        ReviewPage secondPage = controller.reviewPage(
                "test-secret", "PENDING", "HIGH_IMPACT", 1, 1).getBody();

        assertEquals(2, firstPage.total());
        assertEquals(2, firstPage.totalPages());
        assertEquals(first, firstPage.items().getFirst().reviewId());
        assertEquals(second, secondPage.items().getFirst().reviewId());
        assertEquals(1, secondPage.page());
        assertEquals(1, secondPage.size());
    }

    @Test
    void formalDecisionPathDelegatesWithoutBreakingTheLegacyPath() {
        long formalId = repository.insertReview(event("formal-decision"), "HIGH_IMPACT");

        var response = controller.decideFormal(
                formalId, "test-secret", new Decision("REJECT", "not enough evidence"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("REJECTED", jdbc.sql("SELECT status FROM review_queue WHERE id = :id")
                .param("id", formalId).query(String.class).single());

        long legacyId = repository.insertReview(event("legacy-decision"), "HIGH_IMPACT");
        assertEquals(HttpStatus.OK, controller.decide(
                legacyId, "test-secret", new Decision("REJECT", "legacy stays supported"))
                .getStatusCode());
    }

    @Test
    void approvedReviewAdvancesTheNodeAndConfirmsTheEvent() {
        setLevel("P1-REUSE-LV", 7);
        long eventId = event("approve");
        long reviewId = repository.insertReview(eventId, "HIGH_IMPACT");

        var response = controller.decide(reviewId, "test-secret", new Decision("APPROVE", "verified"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(8, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
        assertEquals("CONFIRMED", eventField(eventId, "event_status"));
        assertEquals("APPROVED", jdbc.sql("SELECT status FROM review_queue WHERE id = :id")
                .param("id", reviewId).query(String.class).single());
    }

    @Test
    void rejectedReviewMarksTheEventRejectedAndLeavesTheNode() {
        setLevel("P1-REUSE-LV", 7);
        long eventId = event("reject");
        long reviewId = repository.insertReview(eventId, "HIGH_IMPACT");

        var response = controller.decide(reviewId, "test-secret", new Decision("REJECT", "not credible"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(7, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
        assertEquals("REJECTED", eventField(eventId, "event_status"));
    }

    @Test
    void frozenApprovalReturnsConflictAndKeepsReviewPending() {
        setLevel("P1-REUSE-LV", 7);
        long eventId = event("frozen-approval");
        long reviewId = repository.insertReview(eventId, "HIGH_IMPACT");
        freezeService.freeze("drift drill", Trigger.DRILL);

        var response = controller.decide(
                reviewId, "test-secret", new Decision("APPROVE", "verified"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("FROZEN", response.getBody().get("error"));
        assertEquals("PENDING", jdbc.sql("SELECT status FROM review_queue WHERE id = :id")
                .param("id", reviewId).query(String.class).single());
        assertEquals(7, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
    }

    @Test
    void decisionOnAnUnknownOrResolvedReviewFails() {
        assertEquals(HttpStatus.NOT_FOUND,
                controller.decide(999_999, "test-secret", new Decision("APPROVE", null)).getStatusCode());

        long eventId = event("resolved");
        long reviewId = repository.insertReview(eventId, "HIGH_IMPACT");
        controller.decide(reviewId, "test-secret", new Decision("REJECT", "duplicate coverage"));
        assertEquals(HttpStatus.CONFLICT,
                controller.decide(reviewId, "test-secret", new Decision("APPROVE", null)).getStatusCode());
    }

    @Test
    void rejectionRequiresANonblankNote() {
        setLevel("P1-REUSE-LV", 7);
        long eventId = event("note-rules");
        long reviewId = repository.insertReview(eventId, "HIGH_IMPACT");

        assertEquals(HttpStatus.BAD_REQUEST,
                controller.decide(reviewId, "test-secret", new Decision("REJECT", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.decide(reviewId, "test-secret", new Decision("REJECT", "   ")).getStatusCode());
        assertEquals("PENDING", jdbc.sql("SELECT status FROM review_queue WHERE id = :id")
                .param("id", reviewId).query(String.class).single());
        // Approval notes stay optional.
        assertEquals(HttpStatus.OK,
                controller.decide(reviewId, "test-secret", new Decision("APPROVE", null)).getStatusCode());
    }

    @Test
    void pendingReviewIncludesEvidenceAndLevelContext() {
        jdbc.sql("UPDATE capability_node SET current_level = 6 WHERE code = 'P1-ORBIT-REFUEL'").update();
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'SPACENEWS'")
                .query(Long.class).single();
        long articleId = repository.insertArticleIfNew(
                "https://spacenews.test/case", "8".repeat(64), sourceId,
                "Refueling milestone story", java.time.Instant.parse("2026-01-30T00:00:00Z"),
                "Full body. The vehicle completed the test.").orElseThrow();
        long rubricId = repository.activeRubricVersionId();
        long nodeId = jdbc.sql("SELECT id FROM capability_node WHERE code = 'P1-ORBIT-REFUEL'")
                .query(Long.class).single();
        long eventId = repository.upsertEventByNaturalKey(
                "P1-ORBIT-REFUEL|FLIGHT_TEST|case|2926",
                com.aienterprise.backend.tracker.domain.EventRow.draft(
                        nodeId, "FLIGHT_TEST", 8, "SpaceX", LocalDate.of(2026, 1, 30),
                        "OFFICIAL", LocalDate.of(2026, 4, 30), rubricId));
        long classificationId = repository.insertClassification(
                new com.aienterprise.backend.tracker.domain.ClassificationRow(
                        0, articleId, null, "P1-ORBIT-REFUEL", "FLIGHT_TEST", 8, "SpaceX",
                        LocalDate.of(2026, 1, 30), "THIRD_PARTY",
                        "The vehicle completed the test.", true, "{}", rubricId));
        repository.linkClassification(classificationId, eventId);
        repository.insertReview(eventId, "HIGH_IMPACT");

        var response = controller.reviewQueue("test-secret");

        var item = response.getBody().get(0);
        assertEquals("P1-ORBIT-REFUEL", item.nodeCode());
        assertEquals(6, item.currentLevel());
        assertEquals(8, item.claimedLevel());
        assertEquals("HIGH_IMPACT", item.reason());
        assertEquals(1, item.sourceCount());
        assertEquals(EvidenceKind.VERBATIM, item.evidence().get(0).kind());
        assertEquals("The vehicle completed the test.",
                item.evidence().get(0).evidenceQuote());
        assertEquals("Refueling milestone story", item.evidence().get(0).articleTitle());
        assertEquals("https://spacenews.test/case", item.evidence().get(0).articleUrl());
    }

    @Test
    void pendingReviewExposesHistoricalReferenceWithoutQuotationField() {
        long eventId = event("historical-reference");
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'NASA'")
                .query(Long.class).single();
        repository.insertHistoricalEvidence(HistoricalEvidenceRow.draft(
                "BF-ADMIN-HISTORICAL", "HC-ADMIN-HISTORICAL", "MONTH",
                eventId, sourceId, "https://www.nasa.gov/historical-reference",
                "mission facts section", LocalDate.of(2026, 7, 13), "d".repeat(64),
                "PRIMARY", "Reviewer-authored historical fact summary.",
                "Fact and rubric reviewed."));
        repository.insertReview(eventId, "LEVEL_JUMP");

        var item = controller.reviewQueue("test-secret").getBody().get(0);

        assertEquals(1, item.sourceCount());
        assertEquals(1, item.evidence().size());
        var evidence = item.evidence().get(0);
        assertEquals(EvidenceKind.HISTORICAL_REFERENCE, evidence.kind());
        assertEquals("National Aeronautics and Space Administration", evidence.sourceLabel());
        assertEquals("https://www.nasa.gov/historical-reference", evidence.url());
        assertEquals(null, evidence.evidenceQuote());
        assertEquals("Reviewer-authored historical fact summary.", evidence.factSummary());
        assertEquals("mission facts section", evidence.locator());
        assertEquals(LocalDate.of(2026, 7, 13), evidence.accessedOn());
    }

    @Test
    void casesAreOrderedByPriorityDescendingThenOldestFirst() {
        long normal = repository.insertReview(event("older-normal"), "HIGH_IMPACT");
        long urgent = repository.insertReview(event("newer-urgent"), "HIGH_IMPACT");
        repository.recordFlukeFailure(urgent, "boom", 1);

        var cases = controller.reviewQueue("test-secret").getBody();

        assertEquals(urgent, cases.get(0).reviewId());
        assertEquals(normal, cases.get(1).reviewId());
    }

    private void setLevel(String nodeCode, int level) {
        jdbc.sql("UPDATE capability_node SET current_level = :level WHERE code = :code")
                .param("level", level).param("code", nodeCode).update();
    }

    private long event(String key) {
        long nodeId = jdbc.sql("SELECT id FROM capability_node WHERE code = 'P1-REUSE-LV'")
                .query(Long.class).single();
        return repository.upsertEventByNaturalKey(
                "P1-REUSE-LV|OPERATIONAL_DEPLOYMENT|" + key + "|2926",
                EventRow.draft(nodeId, "OPERATIONAL_DEPLOYMENT", 8, "SpaceX", LocalDate.of(2026, 1, 30),
                        "OFFICIAL", LocalDate.of(2026, 4, 30), repository.activeRubricVersionId()));
    }

    private String eventField(long eventId, String column) {
        return jdbc.sql("SELECT " + column + " FROM event WHERE id = :id")
                .param("id", eventId).query(String.class).single();
    }
}
