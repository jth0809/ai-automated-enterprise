package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class HumanPresenceAggregatorTest {

    private final HumanPresenceAggregator aggregator = new HumanPresenceAggregator();

    @Test
    void integratesLeapYearIntervalsAndExcludesDraftAfterCutoff() {
        HumanPresenceDataset dataset = dataset(
                "2025-01-01T00:00:00Z",
                transition("2024-01-01T00:00:00Z", 2),
                transition("2024-07-01T00:00:00Z", 4),
                transition("2025-01-01T00:00:00Z", 3),
                transition("2025-02-01T00:00:00Z", 50));

        List<HumanPresenceYear> years = aggregator.aggregate(dataset);

        assertEquals(1, years.size());
        assertEquals(2024, years.getFirst().year());
        assertDecimal("1100.0000", years.getFirst().personDays());
        assertEquals(4, years.getFirst().maxOrbitPopulation());
    }

    @Test
    void clipsPersistedPopulationAtEveryCalendarYearBoundary() {
        HumanPresenceDataset dataset = dataset(
                "2025-01-01T00:00:00Z",
                transition("2023-01-01T00:00:00Z", 1),
                transition("2024-01-01T00:00:00Z", 2),
                transition("2024-07-01T00:00:00Z", 4),
                transition("2025-01-01T00:00:00Z", 3));

        List<HumanPresenceYear> years = aggregator.aggregate(dataset);

        assertEquals(List.of(2023, 2024), years.stream().map(HumanPresenceYear::year).toList());
        assertDecimal("365.0000", years.get(0).personDays());
        assertEquals(1, years.get(0).maxOrbitPopulation());
        assertDecimal("1100.0000", years.get(1).personDays());
        assertEquals(4, years.get(1).maxOrbitPopulation());
    }

    @Test
    void roundsAnnualPersonDaysHalfUpOnlyAfterExactSecondIntegration() {
        HumanPresenceDataset dataset = dataset(
                "2025-01-01T00:00:00Z",
                transition("2024-01-01T00:00:00Z", 1),
                transition("2024-01-01T00:00:05Z", 0),
                transition("2025-01-01T00:00:00Z", 0));

        HumanPresenceYear result = aggregator.aggregate(dataset).getFirst();

        assertDecimal("0.0001", result.personDays());
        assertEquals(1, result.maxOrbitPopulation());
    }

    private static HumanPresenceDataset dataset(
            String completeThrough, HumanPresenceTransition... transitions) {
        return new HumanPresenceDataset(
                "human-presence-v9",
                "Reviewed orbital population history",
                "https://planet4589.org/space/astro/web/pop.html",
                LocalDate.of(2026, 7, 16),
                Instant.parse(completeThrough),
                List.of(transitions));
    }

    private static HumanPresenceTransition transition(String instant, int population) {
        return new HumanPresenceTransition(Instant.parse(instant), population);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
