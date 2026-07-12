package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.ingest.BackfillLoader;
import com.aienterprise.backend.tracker.math.SnapshotJob;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tracker.enabled=true",
                "tracker.backfill-on-boot=false",
                "tracker.backfill-resource=tracker/backfill-sample.json"})
@ActiveProfiles("test")
@Transactional
class TrackerControllerTest {

    @Autowired
    private BackfillLoader loader;

    @Autowired
    private SnapshotJob job;

    @Autowired
    private TrackerController controller;

    @Test
    void summaryMatchesThePublicContract() {
        loader.loadIfEmpty();
        job.snapshotNow();

        var response = controller.summary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(Set.of("displayedEtaYear", "etaLow", "etaHigh", "label",
                "overallReadiness", "bottleneckPillar", "frozen"), body.keySet());
        assertEquals("현 추세 지속 시나리오 기준 · 모델 내 80% 구간", body.get("label"));
        assertEquals(false, body.get("frozen"));
        assertEquals(0.0, (Double) body.get("overallReadiness"), 1e-9);
        // Pillar 3 has no backfill events, so it is the readiness bottleneck.
        assertEquals(3, body.get("bottleneckPillar"));
    }

    @Test
    void pillarsReturnSixEntriesWithTheContractFields() {
        loader.loadIfEmpty();
        job.snapshotNow();

        List<Map<String, Object>> pillars = controller.pillars().getBody();

        assertEquals(6, pillars.size());
        for (Map<String, Object> pillar : pillars) {
            assertEquals(Set.of("pillar", "name", "readiness", "etaYear", "momentum"), pillar.keySet());
        }
        assertEquals(1, pillars.get(0).get("pillar"));
    }

    @Test
    void eventsTimelineIsNewestFirstWithTheContractFields() {
        loader.loadIfEmpty();

        List<Map<String, Object>> events = controller.events(50).getBody();

        assertEquals(8, events.size());
        assertEquals(Set.of("occurredOn", "nodeName", "eventType", "levelFrom", "levelTo",
                "impactScore", "verificationLevel", "sourceCount", "evidenceQuote"),
                events.get(0).keySet());
        assertEquals(LocalDate.of(2021, 4, 20), events.get(0).get("occurredOn"));
        assertTrue(events.stream().allMatch(e -> e.get("sourceCount") != null));
    }
}
