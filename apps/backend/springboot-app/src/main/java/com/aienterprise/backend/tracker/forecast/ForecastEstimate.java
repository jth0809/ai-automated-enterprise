package com.aienterprise.backend.tracker.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One explicitly labelled estimate or non-estimate in the comparison matrix. */
public record ForecastEstimate(
        String status,
        BigDecimal year,
        BigDecimal rawYear,
        BigDecimal yearLow,
        BigDecimal yearHigh,
        String relationKind,
        String label,
        String detail,
        String sourceName,
        String sourceUrl,
        String sourceLocator,
        LocalDate observedOn,
        LocalDate accessedOn,
        boolean legacy) {
}
