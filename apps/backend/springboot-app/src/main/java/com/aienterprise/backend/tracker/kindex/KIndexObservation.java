package com.aienterprise.backend.tracker.kindex;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Immutable annual K-index observation and its reviewed provenance. */
public record KIndexObservation(
        int year,
        BigDecimal primaryEnergyTwh,
        long powerWatts,
        BigDecimal kValue,
        String accountingBasis,
        String sourceName,
        String sourceUrl,
        LocalDate accessedOn,
        String datasetVersion) {
}
