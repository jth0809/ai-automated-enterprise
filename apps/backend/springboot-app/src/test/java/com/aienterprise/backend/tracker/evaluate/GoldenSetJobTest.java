package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import com.aienterprise.backend.tracker.domain.OpsState;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@ExtendWith(MockitoExtension.class)
class GoldenSetJobTest {

    private static final Instant NOW = Instant.parse("2026-07-19T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final GoldenSetEvaluator.VersionTuple VERSIONS =
            new GoldenSetEvaluator.VersionTuple(
                    "golden-v1", "classify-prompt-v1", "claude-opus-4-8",
                    "r2.0", "golden-output-v1");

    @Mock
    private GoldenSetEvaluator evaluator;

    @Mock
    private TrackerRepository repository;

    private GoldenSetJob job;

    @BeforeEach
    void setUp() {
        job = new GoldenSetJob(evaluator, repository, true, true, VERSIONS, CLOCK);
    }

    @Test
    void schedulerUsesSundayUtcCadenceAndShedLock() throws Exception {
        Method method = GoldenSetJob.class.getMethod("runWeekly");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertEquals("${tracker.golden-cron:0 0 2 * * SUN}", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
        assertEquals("tracker-golden-set", lock.name());
        assertEquals("PT1M", lock.lockAtLeastFor());
    }

    @Test
    void disabledScheduleNeverReachesAnthropicClient() {
        AnthropicClient anthropic = mock(AnthropicClient.class);
        DeepClassifier deep = new DeepClassifier(
                anthropic, mock(CostGuard.class), repository, "model");
        GoldenSetEvaluator realEvaluator = new GoldenSetEvaluator(
                repository, mock(GoldenSetLoader.class),
                new DeepClassifierGoldenAdapter(deep));
        GoldenSetJob disabled = new GoldenSetJob(
                realEvaluator, repository, false, true, VERSIONS, CLOCK);

        disabled.runWeekly();

        verifyNoInteractions(anthropic);
        verify(repository).putOpsState(
                GoldenSetJob.LAST_RUN_STATUS_KEY, "SKIPPED_DISABLED");
    }

    @Test
    void missingApiConfigurationSkipsWithoutEvaluation() {
        GoldenSetJob missingApi = new GoldenSetJob(
                evaluator, repository, true, false, VERSIONS, CLOCK);

        missingApi.runWeekly();

        verify(evaluator, never()).evaluate(any(), any());
        verify(repository).putOpsState(
                GoldenSetJob.LAST_RUN_STATUS_KEY, "SKIPPED_API_UNAVAILABLE");
    }

    @Test
    void onlyPassingLiveRunUpdatesOperationalBaseline() {
        when(repository.findOpsState(GoldenSetJob.LIVE_ACTIVATED_KEY))
                .thenReturn(Optional.empty());
        when(evaluator.evaluate(GoldenSetEvaluator.RunMode.LIVE_MODEL, VERSIONS))
                .thenReturn(report(GoldenSetEvaluator.RunMode.LIVE_MODEL, 50, 45, 0, 0.90));

        GoldenSetEvaluator.Report live = job.runForMode(GoldenSetEvaluator.RunMode.LIVE_MODEL);

        assertEquals(0.90, live.agreement());
        verify(repository).putOpsState(
                GoldenSetJob.LAST_LIVE_SUCCESS_KEY, NOW.toString());
        verify(repository).putOpsState(
                eq(GoldenSetJob.LIVE_ACTIVATED_KEY),
                argThat((String value) -> value.matches("[0-9a-f]{64}")));
        verify(repository).putOpsState(GoldenSetJob.BASELINE_REQUIRED_KEY, "false");
    }

    @Test
    void offlineAndDrillRunsNeverChangeLiveBaseline() {
        when(evaluator.evaluate(GoldenSetEvaluator.RunMode.OFFLINE_REPLAY, VERSIONS))
                .thenReturn(report(GoldenSetEvaluator.RunMode.OFFLINE_REPLAY, 50, 50, 0, 1.0));
        when(evaluator.evaluate(GoldenSetEvaluator.RunMode.DRILL, VERSIONS))
                .thenReturn(report(GoldenSetEvaluator.RunMode.DRILL, 50, 44, 0, 0.88));

        job.runForMode(GoldenSetEvaluator.RunMode.OFFLINE_REPLAY);
        job.runForMode(GoldenSetEvaluator.RunMode.DRILL);

        verify(repository, never()).putOpsState(eq(GoldenSetJob.LAST_LIVE_SUCCESS_KEY), any());
        verify(repository, never()).putOpsState(eq(GoldenSetJob.LIVE_ACTIVATED_KEY), any());
        verify(repository, never()).putOpsState(eq(GoldenSetJob.BASELINE_REQUIRED_KEY), any());
    }

    @Test
    void changedVersionTupleRequiresNewPassingBaselineBeforeActivation() {
        when(repository.findOpsState(GoldenSetJob.LIVE_ACTIVATED_KEY))
                .thenReturn(Optional.of(new OpsState("0".repeat(64), NOW.minusSeconds(86_400))));
        when(evaluator.evaluate(GoldenSetEvaluator.RunMode.LIVE_MODEL, VERSIONS))
                .thenReturn(report(GoldenSetEvaluator.RunMode.LIVE_MODEL, 50, 44, 0, 0.88));

        GoldenSetEvaluator.Report report = job.runForMode(GoldenSetEvaluator.RunMode.LIVE_MODEL);

        assertEquals(0.88, report.agreement());
        InOrder order = inOrder(repository, evaluator);
        order.verify(repository).putOpsState(GoldenSetJob.BASELINE_REQUIRED_KEY, "true");
        order.verify(evaluator).evaluate(GoldenSetEvaluator.RunMode.LIVE_MODEL, VERSIONS);
        verify(repository).putOpsState(GoldenSetJob.LAST_LIVE_SUCCESS_KEY, NOW.toString());
        verify(repository, never()).putOpsState(
                eq(GoldenSetJob.LIVE_ACTIVATED_KEY),
                argThat((String value) -> !value.equals("0".repeat(64))));
    }

    @Test
    void rejectsAnyReportBeyondBoundedBatchBeforeBaselineUpdate() {
        when(repository.findOpsState(GoldenSetJob.LIVE_ACTIVATED_KEY))
                .thenReturn(Optional.empty());
        when(evaluator.evaluate(GoldenSetEvaluator.RunMode.LIVE_MODEL, VERSIONS))
                .thenReturn(report(GoldenSetEvaluator.RunMode.LIVE_MODEL, 61, 61, 0, 1.0));

        assertThrows(IllegalStateException.class,
                () -> job.runForMode(GoldenSetEvaluator.RunMode.LIVE_MODEL));

        verify(repository, never()).putOpsState(eq(GoldenSetJob.LAST_LIVE_SUCCESS_KEY), any());
        verify(repository, never()).putOpsState(eq(GoldenSetJob.LIVE_ACTIVATED_KEY), any());
    }

    private static GoldenSetEvaluator.Report report(
            GoldenSetEvaluator.RunMode mode,
            int total,
            int matched,
            int failed,
            double agreement) {
        return new GoldenSetEvaluator.Report(
                10L, mode, failed == 0 ? "SUCCEEDED" : "FAILED",
                total, matched, failed, total - matched - failed,
                agreement, VERSIONS);
    }
}
