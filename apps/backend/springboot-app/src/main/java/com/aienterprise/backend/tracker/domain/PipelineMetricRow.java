package com.aienterprise.backend.tracker.domain;

import java.time.LocalDate;

public record PipelineMetricRow(
        LocalDate metricDate,
        String metricCode,
        double metricValue,
        Double baselineMean,
        Double lowerBound,
        Double upperBound,
        String monitorStatus,
        boolean violation,
        int consecutiveViolations,
        int sampleDays) {
}
