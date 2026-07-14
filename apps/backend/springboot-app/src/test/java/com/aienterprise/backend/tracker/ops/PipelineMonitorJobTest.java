package com.aienterprise.backend.tracker.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import com.aienterprise.backend.tracker.domain.FeedPublicationWindow;
import com.aienterprise.backend.tracker.domain.PipelineDailyAggregate;
import com.aienterprise.backend.tracker.domain.PipelineMetricRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ops.StateFreezeService.Trigger;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@ExtendWith(MockitoExtension.class)
class PipelineMonitorJobTest {

    private static final LocalDate METRIC_DATE = LocalDate.parse("2026-07-13");

    @Mock
    private TrackerRepository repository;

    @Mock
    private StateFreezeService freezeService;

    private PipelineMonitorJob job;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-14T01:20:00Z"), ZoneOffset.UTC);
        job = new PipelineMonitorJob(repository, freezeService, clock);
    }

    @Test
    void dailySchedulerUsesUtcAndShedLock() throws Exception {
        Method method = PipelineMonitorJob.class.getMethod("runOnce");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertEquals("${tracker.monitor-cron:0 20 1 * * *}", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
        assertEquals("tracker-pipeline-monitor", lock.name());
        assertEquals("PT1M", lock.lockAtLeastFor());
    }

    @Test
    void secondConsecutiveViolationPersistsFourMetricsAndFreezesOnce() {
        when(repository.aggregatePipelineMetrics(METRIC_DATE))
                .thenReturn(new PipelineDailyAggregate(0.5, 5, 2.0, 3.0));
        when(repository.findRecentPipelineMetrics(
                PipelineMonitorJob.RELEVANCE_GATE_PASS_RATE, METRIC_DATE, 28))
                .thenReturn(history(PipelineMonitorJob.RELEVANCE_GATE_PASS_RATE, 0.5, 0));
        when(repository.findRecentPipelineMetrics(
                PipelineMonitorJob.CONFIRMED_EVENT_COUNT, METRIC_DATE, 28))
                .thenReturn(history(PipelineMonitorJob.CONFIRMED_EVENT_COUNT, 1.0, 1));
        when(repository.findRecentPipelineMetrics(
                PipelineMonitorJob.IMPACT_MEDIAN, METRIC_DATE, 28))
                .thenReturn(history(PipelineMonitorJob.IMPACT_MEDIAN, 2.0, 0));
        when(repository.findRecentPipelineMetrics(
                PipelineMonitorJob.IMPACT_P95, METRIC_DATE, 28))
                .thenReturn(history(PipelineMonitorJob.IMPACT_P95, 3.0, 0));

        job.runOnce();

        ArgumentCaptor<PipelineMetricRow> rows = ArgumentCaptor.forClass(PipelineMetricRow.class);
        verify(repository, org.mockito.Mockito.times(4)).upsertPipelineMetric(rows.capture());
        PipelineMetricRow count = rows.getAllValues().stream()
                .filter(row -> row.metricCode().equals(PipelineMonitorJob.CONFIRMED_EVENT_COUNT))
                .findFirst().orElseThrow();
        assertEquals("TRIGGERED", count.monitorStatus());
        assertEquals(2, count.consecutiveViolations());
        verify(freezeService).freeze(
                contains(PipelineMonitorJob.CONFIRMED_EVENT_COUNT), eq(Trigger.AUTOMATIC));
    }

    @Test
    void deadmanAlertIsExposedInBoundedOpsStateWithoutFreezingScoring() {
        Instant now = Instant.parse("2026-07-14T01:20:00Z");
        when(repository.findActiveFeedPublicationWindows(64)).thenReturn(List.of(
                new FeedPublicationWindow(1L, "ALERT_FEED", List.of(
                        now.minusSeconds(5 * 3_600L),
                        now.minusSeconds(4 * 3_600L),
                        now.minusSeconds(3 * 3_600L),
                        now.minusSeconds(2 * 3_600L + 1))),
                new FeedPublicationWindow(2L, "OK_FEED", List.of(
                        now.minusSeconds(5 * 3_600L),
                        now.minusSeconds(4 * 3_600L),
                        now.minusSeconds(3 * 3_600L),
                        now.minusSeconds(2 * 3_600L)))));

        job.monitorDeadman(now);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(repository).putOpsState(eq(PipelineMonitorJob.FEED_DEADMAN_STATUS_KEY), value.capture());
        assertTrue(value.getValue().length() <= 4_000);
        assertTrue(value.getValue().contains("\"source\":\"ALERT_FEED\""));
        assertTrue(value.getValue().contains("\"status\":\"ALERT\""));
        assertTrue(value.getValue().contains("\"source\":\"OK_FEED\""));
        assertTrue(value.getValue().contains("\"status\":\"OK\""));
        verify(freezeService, never()).freeze(any(), any());
    }

    private static List<PipelineMetricRow> history(
            String code, double value, int latestConsecutive) {
        return java.util.stream.IntStream.range(0, 14)
                .mapToObj(index -> new PipelineMetricRow(
                        METRIC_DATE.minusDays(index + 1L), code, value,
                        value, value, value, "OK", false,
                        index == 0 ? latestConsecutive : 0, 14))
                .toList();
    }
}
