package com.aienterprise.backend.tracker.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A measured Layer B indicator: a numeric value with provenance metadata and an
 * authored summary. No external source body, quote, or binary is carried. The
 * {@code basis} distinguishes MEASURED counts/rates, PUBLISHED_PRICE list-price
 * estimates, and CONSTRUCTED composite indices (concept v2.10 honesty).
 */
public record LayerBMetric(
        long id, String metricCode, int pillar, LocalDate observedOn, BigDecimal value,
        String unit, String basis, String sourceLabel, String sourceUrl,
        LocalDate accessedOn, String contentSha256, String factSummary) {
}
