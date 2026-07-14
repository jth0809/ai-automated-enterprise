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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LayerBApiTest {

    @Autowired
    private LayerBLoader loader;

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
        // The internal provenance hash is never exposed on the public contract.
        assertFalse(first.containsKey("contentSha256"));
        Set<String> bases = Set.of("MEASURED", "PUBLISHED_PRICE", "CONSTRUCTED");
        for (Map<String, Object> entry : body) {
            assertTrue(bases.contains(entry.get("basis")), () -> "unexpected basis " + entry.get("basis"));
        }
    }
}
