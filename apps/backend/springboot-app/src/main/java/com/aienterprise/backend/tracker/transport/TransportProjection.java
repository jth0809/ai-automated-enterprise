package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Reproducible Wright-law scenario output under a versioned assumption. */
public record TransportProjection(
        long id,
        LocalDate asOfDate,
        String assumptionVersion,
        String modelVersion,
        String status,
        String sufficiencyTier,
        List<String> qualificationFlags,
        int observationCount,
        Double alpha,
        Double beta,
        Double rSquared,
        long currentCumulativeLaunches,
        Double centralCadence,
        Double fastCadence,
        Double slowCadence,
        BigDecimal centralTargetUsdPerKg,
        BigDecimal easyTargetUsdPerKg,
        BigDecimal hardTargetUsdPerKg,
        Double centralRequiredLaunches,
        Double easyRequiredLaunches,
        Double hardRequiredLaunches,
        Double centralEtaYear,
        Double earliestEtaYear,
        Double latestEtaYear,
        boolean centralBeyondHorizon,
        boolean earliestBeyondHorizon,
        boolean latestBeyondHorizon,
        int priceBasisYear,
        int horizonYears,
        String intervalKind,
        String basis,
        String priceMeaning,
        String projectionLabel,
        String reasonCode) {

    public TransportProjection {
        qualificationFlags = qualificationFlags == null
                ? List.of()
                : List.copyOf(qualificationFlags);
    }
}
