package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LayerBDatasetValidatorTest {

    @Test
    void productionSeedValidatesCleanly() {
        var result = new LayerBDatasetValidator()
                .validate(new ClassPathResource("tracker/layer-b-metrics-v1.json"));
        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertTrue(result.metrics().size() >= 3);
    }

    @Test
    void rejectsUnknownCodeBadBasisAndProhibitedKey() {
        var result = new LayerBDatasetValidator().validateJson("""
                [{"metricCode":"UNKNOWN","pillar":9,"observedOn":"2026-01-01","value":-1,
                  "unit":"X","basis":"GUESS","sourceLabel":"L","sourceUrl":"https://x.test",
                  "accessedOn":"2026-07-14","contentSha256":"zz","factSummary":"s","body":"leak"}]
                """);
        assertFalse(result.errors().isEmpty());
    }
}
