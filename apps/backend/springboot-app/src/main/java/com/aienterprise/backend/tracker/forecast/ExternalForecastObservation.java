package com.aienterprise.backend.tracker.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One dated external numeric observation; institution metadata is separate. */
public record ExternalForecastObservation(
        long id,
        String forecastKey,
        String sourceType,
        String sourceName,
        String question,
        BigDecimal forecastYear,
        BigDecimal smoothedYear,
        LocalDate retrievedOn,
        String observationSha256,
        String observationStatus,
        int smoothingWindowDays) {
}
