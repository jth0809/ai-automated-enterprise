package com.aienterprise.backend.tracker.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TransportEconomicsProductionCorpusTest {

    private final TransportEconomicsDatasetValidator validator =
            new TransportEconomicsDatasetValidator();

    @Test
    void reviewedCorpusIsValidSparseAndProvisionalEligible() {
        var dataset = validator.validate(
                new ClassPathResource("tracker/transport-economics-v1.json"));

        assertTrue(dataset.errors().isEmpty(),
                () -> String.join(System.lineSeparator(), dataset.errors()));
        assertEquals("transport-economics-v1", dataset.datasetVersion());
        assertEquals(0, dataset.assumption().centralTargetUsdPerKg()
                .compareTo(new BigDecimal("200")));
        assertEquals(0, dataset.assumption().easyTargetUsdPerKg()
                .compareTo(new BigDecimal("500")));
        assertEquals(0, dataset.assumption().hardTargetUsdPerKg()
                .compareTo(new BigDecimal("100")));
        assertEquals(2025, dataset.assumption().priceBasisYear());
        assertEquals(150, dataset.assumption().horizonYears());

        assertEquals(4, dataset.cpi().size());
        assertEquals(15, dataset.annualCounts().size());
        assertEquals(4, dataset.observations().size());
        assertEquals(3, dataset.annualFrontier().size());
        assertEquals("FALCON_HEAVY_EXPENDABLE",
                dataset.annualFrontier().get(2019).vehicleVariant());
        assertEquals(0, launches(dataset, 2011));
        assertEquals(8, launches(dataset, 2016));

        assertTrue(dataset.observations().stream().allMatch(observation ->
                "FALCON".equals(observation.vehicleFamily())
                        && observation.vehicleVariant().startsWith("FALCON_")
                        && observation.sourceUrl().startsWith("https://")
                        && observation.contentSha256().matches("[0-9a-f]{64}")));
        assertTrue(dataset.cpi().stream().allMatch(evidence ->
                "CUUR0000SA0".equals(evidence.seriesId())
                        && evidence.sourceUrl().startsWith("https://")
                        && evidence.contentSha256().matches("[0-9a-f]{64}")));
        assertTrue(dataset.annualCounts().stream().allMatch(evidence ->
                evidence.sourceUrl().startsWith("https://")
                        && evidence.contentSha256().matches("[0-9a-f]{64}")));
    }

    private static long launches(
            TransportEconomicsDatasetValidator.ValidatedTransportDataset dataset,
            int year) {
        return dataset.annualCounts().stream()
                .filter(evidence -> evidence.count().year() == year)
                .findFirst()
                .orElseThrow()
                .count()
                .launches();
    }
}
