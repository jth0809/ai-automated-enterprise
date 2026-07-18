package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.math.BigDecimal;
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
import com.aienterprise.backend.tracker.domain.EvidenceKind;
import com.aienterprise.backend.tracker.domain.ReviewEvidence;
import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.transport.TransportCoherenceReport;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tracker.enabled=true",
                "tracker.backfill-on-boot=false",
                "tracker.backfill-resource=tracker/backfill-sample.json",
                "tracker.backfill-candidates-resource=tracker/backfill/historical-candidates-import.jsonl",
                "tracker.backfill-dataset-version=backfill-test-v1"})
@ActiveProfiles("test")
@Transactional
class TrackerControllerTest {

    @Autowired
    private BackfillLoader loader;

    @Autowired
    private SnapshotJob job;

    @Autowired
    private TrackerController controller;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private TransportEconomicsRepository transportRepository;

    @Test
    void summaryMatchesThePublicContract() {
        loader.loadIfEmpty();
        job.snapshotNow();

        var response = controller.summary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(Set.of("displayedEtaYear", "etaLow", "etaHigh", "label",
                "overallReadiness", "bottleneckPillar", "frozen",
                "indicatorStatus", "readinessBottleneckPillars",
                "etaBottleneckPillars", "unresolvedEtaPillars",
                "missingPillars", "snapshotDate", "paramsVersion",
                "graphVersion"), body.keySet());
        assertEquals("현 추세 지속 시나리오 · 모델 내부 민감도 80% 구간",
                body.get("label"));
        assertEquals(false, body.get("frozen"));
        assertEquals(0.0, (Double) body.get("overallReadiness"), 1e-9);
        // Pillars 3 and 5 tie at zero in the sample; the legacy alias stays first.
        assertEquals(3, body.get("bottleneckPillar"));
        assertEquals(List.of(3, 5), body.get("readinessBottleneckPillars"));
        assertEquals("COMPLETE", body.get("indicatorStatus"));
        assertEquals(List.of(), body.get("missingPillars"));
    }

    @Test
    void readinessAndEtaBottlenecksChangeOnlyFromSnapshotData() {
        LocalDate date = LocalDate.of(2099, 1, 5);
        replaceIndicatorSnapshots(date,
                List.of(.40, .30, .147, .20, .50, .25),
                List.of(2072.1, 2085.1, 2086.7, 2090.0, 2064.5, 2089.8));

        Map<String, Object> first = controller.summary().getBody();

        assertEquals(List.of(3), first.get("readinessBottleneckPillars"));
        assertEquals(List.of(4), first.get("etaBottleneckPillars"));
        assertEquals(3, first.get("bottleneckPillar"));
        assertEquals(date, first.get("snapshotDate"));
        assertEquals("params-v2", first.get("paramsVersion"));
        assertEquals("graph-v1.0", first.get("graphVersion"));

        replaceIndicatorSnapshots(date,
                List.of(.40, .10, .147, .20, .50, .25),
                List.of(2072.1, 2085.1, 2086.7, 2089.9, 2064.5, 2092.0));

        Map<String, Object> changed = controller.summary().getBody();

        assertEquals(List.of(2), changed.get("readinessBottleneckPillars"));
        assertEquals(List.of(6), changed.get("etaBottleneckPillars"));
        assertEquals(2, changed.get("bottleneckPillar"));
    }

    @Test
    void pillarsReturnSixEntriesWithTheContractFields() {
        loader.loadIfEmpty();
        job.snapshotNow();

        List<Map<String, Object>> pillars = controller.pillars().getBody();

        assertEquals(6, pillars.size());
        for (Map<String, Object> pillar : pillars) {
            assertEquals(Set.of(
                    "pillar", "name", "readiness", "etaYear", "momentum",
                    "baseEtaLow", "baseEtaHigh", "etaLow", "etaHigh",
                    "coherenceAdjusted", "coherenceReportPeriod"),
                    pillar.keySet());
        }
        assertEquals(1, pillars.get(0).get("pillar"));
    }

    @Test
    void pillarsApplyDivergenceBoundsAtReadTimeWithoutChangingSnapshot() {
        LocalDate period = LocalDate.of(2026, 6, 30);
        repository.replaceSnapshot(new SnapshotRow(
                0, 1, period, 0.4, -0.4, 0.1, 0.1,
                4, 10, 2098.4, 2074.2, 2122.6, 2098.4, "eta-v2.10"));
        transportRepository.saveCoherenceReport(new TransportCoherenceReport(
                0, period, period,
                "ADVANCING", "ADVANCING", "ADVANCING", "FLAT",
                "DIVERGENT", "B_AHEAD", 2, true,
                new BigDecimal("1.50"), period));

        Map<String, Object> pillarOne = controller.pillars().getBody().get(0);

        assertEquals(2074.2, (Double) pillarOne.get("baseEtaLow"), 1e-9);
        assertEquals(2122.6, (Double) pillarOne.get("baseEtaHigh"), 1e-9);
        assertEquals(2062.1, (Double) pillarOne.get("etaLow"), 1e-9);
        assertEquals(2134.7, (Double) pillarOne.get("etaHigh"), 1e-9);
        assertEquals(true, pillarOne.get("coherenceAdjusted"));
        assertEquals(period, pillarOne.get("coherenceReportPeriod"));
        SnapshotRow persisted = repository.findLatestSnapshot(1).orElseThrow();
        assertEquals(2074.2, persisted.etaLow(), 1e-9);
        assertEquals(2122.6, persisted.etaHigh(), 1e-9);
    }

    @Test
    void eventsTimelineIsNewestFirstWithTheContractFields() {
        loader.loadIfEmpty();

        List<Map<String, Object>> events = controller.events(50).getBody();

        assertEquals(8, events.size());
        assertEquals(Set.of("occurredOn", "occurredOnPrecision", "nodeName", "eventType",
                "levelFrom", "levelTo", "impactScore", "verificationLevel",
                "sourceCount", "evidenceQuote", "primaryEvidence"),
                events.get(0).keySet());
        assertEquals(LocalDate.of(2021, 4, 20), events.get(0).get("occurredOn"));
        assertTrue(events.stream().allMatch(e -> e.get("sourceCount") != null));
        ReviewEvidence evidence = (ReviewEvidence) events.get(0).get("primaryEvidence");
        assertEquals(EvidenceKind.HISTORICAL_REFERENCE, evidence.kind());
        assertEquals(null, evidence.evidenceQuote());
        assertTrue(evidence.factSummary().startsWith("Reviewer-authored"));
    }

    private void replaceIndicatorSnapshots(
            LocalDate date, List<Double> readiness, List<Double> etaYears) {
        for (int pillar = 1; pillar <= 6; pillar++) {
            double value = readiness.get(pillar - 1);
            double eta = etaYears.get(pillar - 1);
            repository.replaceSnapshot(new SnapshotRow(
                    0, pillar, date, value, 0.0, .01, .01,
                    4, 10, eta, eta - 2, eta + 2, eta,
                    "params-v2", value, "graph-v1.0"));
        }
    }
}
