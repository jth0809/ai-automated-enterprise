package com.aienterprise.backend.tracker.kindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class KIndexProductionDatasetTest {

    @Test
    void reviewedWorldEnergyDatasetMeetsFlexibleProductionContract()
            throws Exception {
        String csv = new ClassPathResource("tracker/k-index-energy-v1.csv")
                .getContentAsString(StandardCharsets.UTF_8);
        var dataset = new KIndexCsvValidator().validate(csv, Clock.fixed(
                Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));

        assertTrue(dataset.errors().isEmpty(),
                () -> String.join("\n", dataset.errors()));
        assertTrue(dataset.observations().size() >= 10);
        assertEquals(2024, dataset.observations().getLast().year());
        assertEquals("SUBSTITUTION", dataset.metadata().accountingBasis());

        var latest = new KIndexCalculator().calculate(
                dataset.observations().getLast().primaryEnergyTwh());
        assertEquals(new BigDecimal("0.7305"), latest.kValue());
    }
}
