package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record PillarTrendResult(
        int pillar,
        double readiness,
        Double trendFit,
        double trendPrior,
        double trendUsed,
        int eventsInWindow,
        int windowYears,
        Double medianIntervalYears,
        Long regimeBreakId,
        LocalDate regimeStart,
        Double etaYear,
        Double etaLow,
        Double etaHigh,
        double residualSe,
        double slopeStandardError,
        Map<LocalDate, Double> levelShifts,
        int observations) {

    public PillarTrendResult {
        if (pillar < 1 || pillar > 6
                || !Double.isFinite(readiness) || readiness < 0 || readiness > 1
                || !Double.isFinite(trendPrior)
                || !Double.isFinite(trendUsed)
                || eventsInWindow < 0 || windowYears <= 0
                || observations < 0
                || !Double.isFinite(residualSe) || residualSe < 0
                || !Double.isFinite(slopeStandardError) || slopeStandardError < 0) {
            throw new IllegalArgumentException("invalid pillar trend result");
        }
        requireFiniteOrNull(trendFit, "trendFit");
        requireFiniteOrNull(medianIntervalYears, "medianIntervalYears");
        requireFiniteOrNull(etaYear, "etaYear");
        requireFiniteOrNull(etaLow, "etaLow");
        requireFiniteOrNull(etaHigh, "etaHigh");
        levelShifts = Collections.unmodifiableMap(new LinkedHashMap<>(levelShifts));
    }

    private static void requireFiniteOrNull(Double value, String label) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}
