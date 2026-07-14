package com.aienterprise.backend.tracker.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Bounded operational snapshot for the token-gated tracker console. It
 * contains summaries only: no secret, raw model output, article body, or SQL
 * diagnostic is part of this contract.
 */
public record OpsOverview(
        boolean frozen,
        String freezeReason,
        String freezeTrigger,
        Instant freezeAt,
        GoldenRun latestGolden,
        List<ControlMetric> controlMetrics,
        DeadmanSummary deadman) {

    public OpsOverview {
        controlMetrics = List.copyOf(controlMetrics);
    }

    public record GoldenRun(
            long id,
            String mode,
            String status,
            String datasetVersion,
            String promptVersion,
            String modelVersion,
            int totalCount,
            int matchedCount,
            int failedCount,
            Double agreement,
            Instant startedAt,
            Instant completedAt) {
    }

    public record ControlMetric(
            LocalDate metricDate,
            String metricCode,
            double value,
            Double baselineMean,
            Double lowerBound,
            Double upperBound,
            String status,
            boolean violation,
            int consecutiveViolations,
            int sampleDays) {
    }

    public record DeadmanSummary(
            String status,
            String observedAt,
            int feedCount,
            int alertCount,
            int insufficientCount,
            List<DeadmanFeed> feeds) {

        public DeadmanSummary {
            feeds = List.copyOf(feeds);
        }
    }

    public record DeadmanFeed(
            String source,
            String status,
            int intervalSamples,
            Double medianIntervalHours,
            Double silenceHours) {
    }
}
