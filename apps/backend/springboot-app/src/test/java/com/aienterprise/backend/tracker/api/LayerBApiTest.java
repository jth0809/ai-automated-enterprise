package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.ingest.LayerBLoader;
import com.aienterprise.backend.tracker.ingest.HumanPresenceLoader;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LayerBApiTest {

    @Autowired
    private LayerBLoader loader;

    @Autowired
    private HumanPresenceLoader humanPresenceLoader;

    @Autowired
    private TrackerController controller;

    @Test
    void layerBExposesMeasuredMetricsWithBasisAndWithoutHash() {
        loader.loadIfNeeded();

        var response = controller.layerB();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> body = response.getBody();
        assertEquals(3, body.size());
        Map<String, Object> first = body.stream()
                .filter(m -> "LAUNCH_PRICE_LEO".equals(m.get("metricCode"))).findFirst().orElseThrow();
        assertEquals("PUBLISHED_PRICE", first.get("basis"));
        assertEquals("수송", first.get("pillarName"));
        assertTrue(first.containsKey("value") && first.containsKey("unit"));
        assertEquals("2026-07-14", first.get("accessedOn"));
        // The internal provenance hash is never exposed on the public contract.
        assertFalse(first.containsKey("contentSha256"));
        Set<String> bases = Set.of("MEASURED", "PUBLISHED_PRICE", "CONSTRUCTED");
        for (Map<String, Object> entry : body) {
            assertTrue(bases.contains(entry.get("basis")), () -> "unexpected basis " + entry.get("basis"));
        }
    }

    @Test
    void layerBExposesCompletedOrbitalPresenceAsPillarTwoMeasurements() {
        humanPresenceLoader.loadIfNeeded();

        List<Map<String, Object>> body = controller.layerB().getBody();

        // The API intentionally returns only the latest completed year per metric code.
        assertEquals(2, body.size());
        Map<String, Object> personDays = body.stream()
                .filter(entry -> "ANNUAL_ORBITAL_HUMAN_PERSON_DAYS"
                        .equals(entry.get("metricCode")))
                .findFirst().orElseThrow();
        assertEquals(2, personDays.get("pillar"));
        assertEquals("생명 유지", personDays.get("pillarName"));
        assertEquals("PERSON_DAYS", personDays.get("unit"));
        assertEquals("MEASURED", personDays.get("basis"));
        assertEquals("2025-12-31", personDays.get("observedOn"));
        assertEquals("2026-07-16", personDays.get("accessedOn"));
        assertEquals("https://planet4589.org/space/astro/web/pop.html",
                personDays.get("sourceUrl"));
        assertEquals(0, new java.math.BigDecimal("3922.2028")
                .compareTo((java.math.BigDecimal) personDays.get("value")));

        Map<String, Object> maximum = body.stream()
                .filter(entry -> "MAX_SIMULTANEOUS_HUMANS_IN_ORBIT"
                        .equals(entry.get("metricCode")))
                .findFirst().orElseThrow();
        assertEquals("PEOPLE", maximum.get("unit"));
        assertEquals(0, new java.math.BigDecimal("14")
                .compareTo((java.math.BigDecimal) maximum.get("value")));
        assertFalse(personDays.containsKey("contentSha256"));
        assertFalse(maximum.containsKey("contentSha256"));
    }
}
