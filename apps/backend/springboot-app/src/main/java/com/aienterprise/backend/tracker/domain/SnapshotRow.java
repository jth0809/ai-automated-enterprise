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
        String paramsVersion,
        Double rawReadiness,
        String graphVersion) {

    public SnapshotRow(
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
        this(id, pillar, snapshotDate, readiness, logitClipped,
                trendFit, trendUsed, eventsInWindow, windowYears,
                etaYear, etaLow, etaHigh, displayedEtaYear, paramsVersion,
                readiness, null);
    }
}
