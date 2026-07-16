package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One completed UTC quarter's Layer B versus Layer C transport comparison. */
public record TransportCoherenceReport(
        long id,
        LocalDate reportPeriodEnd,
        LocalDate layerCSnapshotDate,
        String priceDirection,
        String cadenceDirection,
        String layerBDirection,
        String layerCDirection,
        String state,
        String polarity,
        int consecutiveQuarterStreak,
        boolean alertActive,
        BigDecimal wideningFactor,
        LocalDate firstDivergentPeriod) {
}
