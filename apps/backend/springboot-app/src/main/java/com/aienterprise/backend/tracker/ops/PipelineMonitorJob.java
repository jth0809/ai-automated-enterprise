package com.aienterprise.backend.tracker.ops;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.ToDoubleFunction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.FeedPublicationWindow;
import com.aienterprise.backend.tracker.domain.PipelineDailyAggregate;
import com.aienterprise.backend.tracker.domain.PipelineMetricRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ops.ControlChart.MetricKind;
import com.aienterprise.backend.tracker.ops.StateFreezeService.Trigger;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class PipelineMonitorJob {

    public static final String FEED_DEADMAN_STATUS_KEY = "FEED_DEADMAN_STATUS";
    public static final String RELEVANCE_GATE_PASS_RATE = "RELEVANCE_GATE_PASS_RATE";
    public static final String CONFIRMED_EVENT_COUNT = "CONFIRMED_EVENT_COUNT";
    public static final String IMPACT_MEDIAN = "IMPACT_MEDIAN";
    public static final String IMPACT_P95 = "IMPACT_P95";

    private static final int HISTORY_DAYS = 28;
    private static final int DEADMAN_TIMESTAMPS_PER_SOURCE = 64;
    private static final int OPS_STATE_VALUE_MAX_LENGTH = 4_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<MetricDefinition> METRICS = List.of(
            new MetricDefinition(
                    RELEVANCE_GATE_PASS_RATE, MetricKind.RATIO,
                    PipelineDailyAggregate::relevanceGatePassRate),
            new MetricDefinition(
                    CONFIRMED_EVENT_COUNT, MetricKind.NON_NEGATIVE,
                    aggregate -> aggregate.confirmedEventCount()),
            new MetricDefinition(
                    IMPACT_MEDIAN, MetricKind.NON_NEGATIVE,
                    PipelineDailyAggregate::impactMedian),
            new MetricDefinition(
                    IMPACT_P95, MetricKind.NON_NEGATIVE,
                    PipelineDailyAggregate::impactP95));

    private final TrackerRepository repository;
    private final StateFreezeService freezeService;
    private final Clock clock;

    @Autowired
    public PipelineMonitorJob(
            TrackerRepository repository,
            StateFreezeService freezeService) {
        this(repository, freezeService, Clock.systemUTC());
    }

    PipelineMonitorJob(
            TrackerRepository repository,
            StateFreezeService freezeService,
            Clock clock) {
        this.repository = repository;
        this.freezeService = freezeService;
        this.clock = clock;
    }

    @Scheduled(cron = "${tracker.monitor-cron:0 20 1 * * *}", zone = "UTC")
    @SchedulerLock(name = "tracker-pipeline-monitor", lockAtLeastFor = "PT1M")
    @Transactional
    public void runOnce() {
        monitor(LocalDate.now(clock).minusDays(1));
        monitorDeadman(clock.instant());
    }

    void monitor(LocalDate metricDate) {
        PipelineDailyAggregate aggregate = repository.aggregatePipelineMetrics(metricDate);
        for (MetricDefinition metric : METRICS) {
            List<PipelineMetricRow> history = repository.findRecentPipelineMetrics(
                    metric.code(), metricDate, HISTORY_DAYS);
            int previousConsecutive = history.isEmpty()
                    ? 0 : history.getFirst().consecutiveViolations();
            ControlChart.Result result = ControlChart.evaluate(
                    history.stream().map(PipelineMetricRow::metricValue).toList(),
                    metric.value().applyAsDouble(aggregate),
                    previousConsecutive,
                    metric.kind());
            PipelineMetricRow row = new PipelineMetricRow(
                    metricDate, metric.code(), metric.value().applyAsDouble(aggregate),
                    result.baselineMean(), result.lowerBound(), result.upperBound(),
                    result.status().name(), result.violation(),
                    result.consecutiveViolations(), result.sampleDays());
            repository.upsertPipelineMetric(row);
            if (result.status() == ControlChart.Status.TRIGGERED) {
                freezeService.freeze(
                        "pipeline control chart triggered: " + metric.code(),
                        Trigger.AUTOMATIC);
            }
        }
    }

    void monitorDeadman(Instant observedAt) {
        List<FeedDeadmanState> feeds = repository
                .findActiveFeedPublicationWindows(DEADMAN_TIMESTAMPS_PER_SOURCE)
                .stream()
                .map(window -> deadmanState(window, observedAt))
                .toList();
        String stateValue;
        try {
            stateValue = JSON.writeValueAsString(
                    new FeedDeadmanSnapshot(observedAt.toString(), feeds));
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("could not serialize feed deadman state", serializationFailure);
        }
        if (stateValue.length() > OPS_STATE_VALUE_MAX_LENGTH) {
            throw new IllegalStateException("feed deadman state exceeds ops_state capacity");
        }
        // A silent feed is an ingestion signal, not evidence that scoring state
        // is corrupt. Expose it locally without freezing or adding alert egress.
        repository.putOpsState(FEED_DEADMAN_STATUS_KEY, stateValue);
    }

    private static FeedDeadmanState deadmanState(
            FeedPublicationWindow window,
            Instant observedAt) {
        DeadmanMonitor.Result result = DeadmanMonitor.evaluate(
                window.publicationTimes(), observedAt);
        return new FeedDeadmanState(
                window.sourceCode(),
                result.status().name(),
                result.intervalSamples(),
                rounded(result.medianIntervalHours()),
                rounded(result.silenceHours()));
    }

    private static Double rounded(Double value) {
        return value == null ? null : Math.round(value * 1_000.0) / 1_000.0;
    }

    private record MetricDefinition(
            String code,
            MetricKind kind,
            ToDoubleFunction<PipelineDailyAggregate> value) {
    }

    private record FeedDeadmanSnapshot(
            String observedAt,
            List<FeedDeadmanState> feeds) {
    }

    private record FeedDeadmanState(
            String source,
            String status,
            int intervalSamples,
            Double medianIntervalHours,
            Double silenceHours) {
    }
}
