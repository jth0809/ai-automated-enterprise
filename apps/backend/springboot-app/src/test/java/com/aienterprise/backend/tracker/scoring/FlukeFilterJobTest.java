package com.aienterprise.backend.tracker.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.ReviewRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.evaluate.CostGuard;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class FlukeFilterJobTest {

    private static final String QUOTE = "The vehicle completed the test.";
    private static final String SHA = "a".repeat(64);

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private FlukeFilter filter;
    private CostGuard costGuard;
    private FlukeFilterJob job;

    @BeforeEach
    void setUp() {
        filter = mock(FlukeFilter.class);
        costGuard = mock(CostGuard.class);
        job = new FlukeFilterJob(filter, costGuard, repository);
    }

    @Test
    void mismatchIsPrioritizedButStillRequiresHumanDecision() {
        long reviewId = pendingReview("mismatch");
        when(costGuard.allow()).thenReturn(true);
        when(filter.evaluate(any()))
                .thenReturn(new FlukeResult("MISMATCH", QUOTE, "{}", "fluke-model", SHA));

        job.processPending();

        ReviewRow row = repository.findReviewById(reviewId).orElseThrow();
        assertEquals("MISMATCH", row.flukeResult());
        assertEquals(1, row.priority());
        assertEquals("PENDING", row.status());
        assertEquals("COMPLETE", row.flukeStatus());
        assertFalse(repository.findEventById(row.eventId()).stateAdvanced());
    }

    @Test
    void matchCompletesAtNormalPriority() {
        long reviewId = pendingReview("match");
        when(costGuard.allow()).thenReturn(true);
        when(filter.evaluate(any()))
                .thenReturn(new FlukeResult("MATCH", QUOTE, "{}", "fluke-model", SHA));

        job.processPending();

        ReviewRow row = repository.findReviewById(reviewId).orElseThrow();
        assertEquals("MATCH", row.flukeResult());
        assertEquals(0, row.priority());
        assertEquals("COMPLETE", row.flukeStatus());
        assertEquals(1, evaluationCount(reviewId));
    }

    @Test
    void costDeferralLeavesCountersUntouched() {
        long reviewId = pendingReview("cost");
        when(costGuard.allow()).thenReturn(false);

        job.processPending();

        verify(filter, never()).evaluate(any());
        ReviewRow row = repository.findReviewById(reviewId).orElseThrow();
        assertEquals("PENDING", row.flukeStatus());
        assertEquals(0, row.flukeFailCount());
    }

    @Test
    void thirdOrdinaryFailureBecomesTerminalWithAttentionPriority() {
        long reviewId = pendingReview("fail");
        when(costGuard.allow()).thenReturn(true);
        when(filter.evaluate(any()))
                .thenThrow(new IllegalArgumentException("quote not found"));

        job.processPending();
        job.processPending();
        job.processPending();
        job.processPending();

        verify(filter, times(3)).evaluate(any());
        ReviewRow row = repository.findReviewById(reviewId).orElseThrow();
        assertEquals("FAILED", row.flukeStatus());
        assertEquals(3, row.flukeFailCount());
        assertEquals(2, row.priority());
        assertEquals("PENDING", row.status(), "a human can still decide from the UI");
    }

    @Test
    void duplicateScansStoreExactlyOneEvaluation() {
        long reviewId = pendingReview("dup");
        when(costGuard.allow()).thenReturn(true);
        when(filter.evaluate(any()))
                .thenReturn(new FlukeResult("MATCH", QUOTE, "{}", "fluke-model", SHA));

        job.processPending();
        job.processPending();

        verify(filter, times(1)).evaluate(any());
        assertEquals(1, evaluationCount(reviewId));
    }

    @Test
    void flukeJobBeanStaysDarkUnlessExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(FlukeFilterJob.class)
                .withPropertyValues("tracker.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(FlukeFilterJob.class));
    }

    private long pendingReview(String key) {
        long nodeId = jdbc.sql("SELECT id FROM capability_node WHERE code = 'P1-REUSE-LV'")
                .query(Long.class).single();
        long eventId = repository.upsertEventByNaturalKey(
                "P1-REUSE-LV|OPERATIONAL_DEPLOYMENT|" + key + "|2926",
                EventRow.draft(nodeId, "OPERATIONAL_DEPLOYMENT", 8, "SpaceX",
                        LocalDate.of(2026, 1, 30), "OFFICIAL", LocalDate.of(2026, 4, 30),
                        repository.activeRubricVersionId()));
        return repository.insertReviewIfAbsent(eventId, "HIGH_IMPACT");
    }

    private int evaluationCount(long reviewId) {
        return jdbc.sql("SELECT COUNT(*) FROM fluke_evaluation WHERE review_id = :id")
                .param("id", reviewId).query(Integer.class).single();
    }
}
