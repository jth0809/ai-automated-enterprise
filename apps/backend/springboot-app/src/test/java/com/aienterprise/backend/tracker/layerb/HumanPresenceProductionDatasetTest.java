package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class HumanPresenceProductionDatasetTest {

    @Test
    void reviewedSnapshotReproducesCompleted2024And2025OrbitalPresence() throws Exception {
        var resource = new ClassPathResource("tracker/human-presence-transitions-v1.csv");
        assertTrue(resource.exists(), "reviewed human-presence CSV must exist");

        var validated = new HumanPresenceCsvValidator()
                .validate(resource.getContentAsByteArray());
        assertTrue(validated.errors().isEmpty(), () -> String.join("\n", validated.errors()));
        assertTrue(validated.dataset().transitions().size() >= 40,
                "production contract must contain enough reviewed changes without pinning an exact count");

        Map<Integer, HumanPresenceYear> years = new HumanPresenceAggregator()
                .aggregate(validated.dataset()).stream()
                .collect(Collectors.toMap(HumanPresenceYear::year, Function.identity()));

        assertEquals(2, years.size());
        assertDecimal("4241.8711", years.get(2024).personDays());
        assertEquals(19, years.get(2024).maxOrbitPopulation());
        assertDecimal("3922.2028", years.get(2025).personDays());
        assertEquals(14, years.get(2025).maxOrbitPopulation());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
