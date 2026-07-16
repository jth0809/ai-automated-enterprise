package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;

/** Immutable declared assumptions for one transport-economics model version. */
public record TransportAssumption(
        String version,
        String modelVersion,
        BigDecimal centralTargetUsdPerKg,
        BigDecimal easyTargetUsdPerKg,
        BigDecimal hardTargetUsdPerKg,
        int priceBasisYear,
        int horizonYears,
        BigDecimal weakFitR2,
        BigDecimal wideningFactor) {
}
