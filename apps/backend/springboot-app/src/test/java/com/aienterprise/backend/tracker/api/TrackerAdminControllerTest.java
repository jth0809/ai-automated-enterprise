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
import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

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
    void decisionOnAnUnknownOrResolvedReviewFails() {
        assertEquals(HttpStatus.NOT_FOUND,
                controller.decide(999_999, "test-secret", new Decision("APPROVE", null)).getStatusCode());

        long eventId = event("resolved");
        long reviewId = repository.insertReview(eventId, "HIGH_IMPACT");
        controller.decide(reviewId, "test-secret", new Decision("REJECT", null));
        assertEquals(HttpStatus.CONFLICT,
                controller.decide(reviewId, "test-secret", new Decision("APPROVE", null)).getStatusCode());
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
