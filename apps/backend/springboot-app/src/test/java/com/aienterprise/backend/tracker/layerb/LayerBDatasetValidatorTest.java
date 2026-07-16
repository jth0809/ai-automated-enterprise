package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void acceptsCanonicalTransportEconomicsMirrorCodesIncludingZeroLaunchYears() {
        var result = new LayerBDatasetValidator().validateJson("""
                [
                  {"metricCode":"LEO_PUBLISHED_PRICE_FRONTIER_REAL_2025","pillar":1,
                   "observedOn":"2018-12-31","value":3485.85,"unit":"USD_PER_KG",
                   "basis":"PUBLISHED_PRICE","sourceLabel":"NASA NTRS",
                   "sourceUrl":"https://ntrs.nasa.gov/citations/20180007067",
                   "accessedOn":"2026-07-15","contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "factSummary":"Constant-dollar published-price frontier."},
                  {"metricCode":"ANNUAL_FALCON_FAMILY_LAUNCH_COUNT","pillar":1,
                   "observedOn":"2011-12-31","value":0,"unit":"LAUNCHES",
                   "basis":"MEASURED","sourceLabel":"Launch Library 2",
                   "sourceUrl":"https://ll.thespacedevs.com/2.3.0/launches/",
                   "accessedOn":"2026-07-15","contentSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "factSummary":"Completed Falcon-family orbital launch count."}
                ]
                """);

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals(2, result.metrics().size());
    }

    @Test
    void acceptsHumanPresenceMetricCodesAsMeasuredPillarTwoFacts() {
        var result = new LayerBDatasetValidator().validateJson("""
                [
                  {"metricCode":"ANNUAL_ORBITAL_HUMAN_PERSON_DAYS","pillar":2,
                   "observedOn":"2025-12-31","value":3922.2028,"unit":"PERSON_DAYS",
                   "basis":"MEASURED","sourceLabel":"Reviewed orbital history",
                   "sourceUrl":"https://planet4589.org/space/astro/web/pop.html",
                   "accessedOn":"2026-07-16","contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "factSummary":"Integrated worldwide orbital population time history."},
                  {"metricCode":"MAX_SIMULTANEOUS_HUMANS_IN_ORBIT","pillar":2,
                   "observedOn":"2025-12-31","value":14,"unit":"PEOPLE",
                   "basis":"MEASURED","sourceLabel":"Reviewed orbital history",
                   "sourceUrl":"https://planet4589.org/space/astro/web/pop.html",
                   "accessedOn":"2026-07-16","contentSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "factSummary":"Maximum simultaneous humans in orbit during the year."}
                ]
                """);

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals(2, result.metrics().size());
    }
}
