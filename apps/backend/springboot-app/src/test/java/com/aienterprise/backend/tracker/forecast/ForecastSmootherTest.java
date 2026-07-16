package com.aienterprise.backend.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class ForecastSmootherTest {

    private final ForecastSmoother smoother = new ForecastSmoother();

    @Test
    void averagesTheInclusiveNinetyDayWindowToOneDecimal() {
        LocalDate asOf = LocalDate.of(2026, 7, 15);
        List<ExternalForecastObservation> values = List.of(
                observation("2040.0", asOf),
                observation("2050.0", asOf.minusDays(30)),
                observation("2060.0", asOf.minusDays(89)),
                observation("2200.0", asOf.minusDays(90)));

        assertEquals(new BigDecimal("2050.0"),
                smoother.mean90Day(values, asOf).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoObservationFallsInsideTheWindow() {
        LocalDate asOf = LocalDate.of(2026, 7, 15);
        assertTrue(smoother.mean90Day(List.of(
                observation("2040.0", asOf.minusDays(90))), asOf).isEmpty());
    }

    @Test
    void rejectsInstitutionalOrMixedQuestionInput() {
        LocalDate asOf = LocalDate.of(2026, 7, 15);
        ExternalForecastObservation institution = new ExternalForecastObservation(
                0, "NASA", "INSTITUTIONAL", "NASA", "Target",
                new BigDecimal("2040.0"), null, asOf, "a".repeat(64), "CURRENT", 90);
        ExternalForecastObservation otherKey = new ExternalForecastObservation(
                0, "OTHER", "CROWD", "METACULUS", "Other",
                new BigDecimal("2050.0"), null, asOf, "b".repeat(64), "CURRENT", 90);

        assertThrows(IllegalArgumentException.class,
                () -> smoother.mean90Day(List.of(institution), asOf));
        assertThrows(IllegalArgumentException.class,
                () -> smoother.mean90Day(List.of(
                        observation("2040.0", asOf), otherKey), asOf));
    }

    private static ExternalForecastObservation observation(String year, LocalDate date) {
        return new ExternalForecastObservation(
                0, "METACULUS-LANDING-METADATA", "CROWD", "METACULUS",
                "Landing", new BigDecimal(year), null, date,
                "a".repeat(64), "CURRENT", 90);
    }
}
