package com.aienterprise.backend.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ForecastProductionDatasetTest {

    @Test
    void productionResourceIsReviewedBoundedAndCarriesNoCrowdValue() throws Exception {
        byte[] bytes = new ClassPathResource(
                "tracker/forecast-reference-v1.json").getContentAsByteArray();
        var dataset = new ForecastReferenceValidator().validate(bytes, Clock.systemUTC());

        assertTrue(dataset.errors().isEmpty(), () -> String.join("\n", dataset.errors()));
        assertTrue(dataset.records().size() >= 8 && dataset.records().size() <= 20);
        assertEquals(Set.of("LANDING", "RETURN", "SETTLEMENT"),
                dataset.records().stream().map(ForecastReference::trackCode)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(dataset.records().stream()
                .filter(record -> "CROWD".equals(record.sourceType()))
                .allMatch(record -> record.forecastYear() == null
                        && record.forecastYearLow() == null
                        && record.forecastYearHigh() == null
                        && "AWAITING_AUTHORIZATION".equals(record.displayStatus())));
    }
}
