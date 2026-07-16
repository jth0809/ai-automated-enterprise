package com.aienterprise.backend.tracker.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Deterministic 90-day arithmetic mean for one crowd question. */
public final class ForecastSmoother {

    public static final int WINDOW_DAYS = 90;

    public Optional<BigDecimal> mean90Day(
            List<ExternalForecastObservation> observations,
            LocalDate asOf) {
        if (observations == null || asOf == null) {
            throw new IllegalArgumentException("Observations and asOf are required");
        }
        Set<String> keys = new HashSet<>();
        for (ExternalForecastObservation item : observations) {
            if (!"CROWD".equals(item.sourceType())) {
                throw new IllegalArgumentException("Only crowd observations can be smoothed");
            }
            keys.add(item.forecastKey());
        }
        if (keys.size() > 1) {
            throw new IllegalArgumentException("A smoothing window must contain one forecast key");
        }

        LocalDate start = asOf.minusDays(WINDOW_DAYS - 1L);
        List<BigDecimal> included = observations.stream()
                .filter(item -> !item.retrievedOn().isBefore(start))
                .filter(item -> !item.retrievedOn().isAfter(asOf))
                .map(ExternalForecastObservation::forecastYear)
                .toList();
        if (included.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal sum = included.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(included.size()),
                1, RoundingMode.HALF_UP));
    }
}
