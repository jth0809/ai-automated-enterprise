package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Audited numeric published-price observation. Carries provenance metadata but
 * never source body, quotation, HTML, PDF, image, or binary content.
 */
public record TransportPriceObservation(
        long id,
        int observationYear,
        String vehicleFamily,
        String vehicleVariant,
        BigDecimal publishedPriceUsd,
        BigDecimal maxLeoPayloadKg,
        BigDecimal nominalUsdPerKg,
        BigDecimal cpiObservationValue,
        BigDecimal cpiBasisValue,
        BigDecimal realBasisUsdPerKg,
        long cumulativeFamilyLaunches,
        String sourceLabel,
        String sourceUrl,
        String sourceLocator,
        LocalDate accessedOn,
        String contentSha256,
        String factSummary) {
}
