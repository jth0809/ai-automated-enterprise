package com.aienterprise.backend.tracker.forecast;

import java.math.BigDecimal;

/** Normalized, value-only result from one authorized date-question response. */
public record MetaculusSnapshot(
        int postId,
        BigDecimal forecastYear,
        long centerEpochSeconds) {
}
