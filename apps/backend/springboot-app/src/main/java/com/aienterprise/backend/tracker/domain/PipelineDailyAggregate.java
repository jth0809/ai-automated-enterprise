package com.aienterprise.backend.tracker.domain;

public record PipelineDailyAggregate(
        double relevanceGatePassRate,
        long confirmedEventCount,
        double impactMedian,
        double impactP95) {
}
