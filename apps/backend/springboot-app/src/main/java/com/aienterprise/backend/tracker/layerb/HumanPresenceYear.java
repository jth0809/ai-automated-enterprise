package com.aienterprise.backend.tracker.layerb;

import java.math.BigDecimal;

/** Deterministically aggregated worldwide orbital-human presence for one year. */
public record HumanPresenceYear(
        int year, BigDecimal personDays, int maxOrbitPopulation) {
}
