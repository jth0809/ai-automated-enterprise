package com.aienterprise.backend.tracker.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Reviewed definition or institutional target used only for comparison. */
public record ForecastReference(
        String forecastKey,
        String sourceType,
        String sourceName,
        String trackCode,
        String question,
        String targetDefinition,
        String displayStatus,
        BigDecimal forecastYear,
        BigDecimal forecastYearLow,
        BigDecimal forecastYearHigh,
        String relationKind,
        String sourceUrl,
        String sourceLocator,
        LocalDate accessedOn,
        String ingestionMode,
        String contentSha256,
        String factSummary) {
}
