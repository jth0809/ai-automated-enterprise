package com.aienterprise.backend.tracker.domain;

import java.time.LocalDate;

public record SnapshotRow(
        long id,
        int pillar,
        LocalDate snapshotDate,
        double readiness,
        double logitClipped,
        Double trendFit,
        Double trendUsed,
        Integer eventsInWindow,
        Integer windowYears,
        Double etaYear,
        Double etaLow,
        Double etaHigh,
        Double displayedEtaYear,
        String paramsVersion) {
}
