package com.aienterprise.backend.tracker.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ingest.TransportEconomicsLoader;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TransportCoherenceServiceTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 6, 30);

    @Autowired
    private TransportCoherenceService service;

    @Autowired
    private TransportEconomicsRepository transportRepository;

    @Autowired
    private TrackerRepository trackerRepository;

    @Autowired
    private TransportEconomicsLoader loader;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void divergenceCreatesExactBoundedSamplesWithoutMutatingDomainTables() {
        loader.loadIfNeeded();
        transportRepository.saveProjection(TransportTestFixtures.projection(PERIOD));
        trackerRepository.replaceSnapshot(snapshot(0.0));
        transportRepository.saveCoherenceReport(
                TransportTestFixtures.watchReport(LocalDate.of(2026, 3, 31)));

        TransportTestFixtures.insertConfirmedPillarOneEvent(
                jdbc, "before-boundary", LocalDate.of(2026, 3, 31),
                new BigDecimal("1.00"));
        long a = event("a", "2026-04-01", "0.90");
        long b = event("b", "2026-05-01", "0.90");
        long c = event("c", "2026-05-01", "0.90");
        long d = event("d", "2026-06-01", "0.80");
        long e = event("e", "2026-06-01", "0.70");
        long f = event("f", "2026-06-01", "0.60");
        long g = event("g", "2026-06-01", "0.50");
        long h = event("h", "2026-06-01", "0.40");
        long i = event("i", "2026-06-01", "0.30");
        long j = event("j", "2026-06-01", "0.20");
        event("k", "2026-06-01", "0.10");
        event("l", "2026-06-01", "0.05");
        TransportTestFixtures.insertConfirmedPillarOneEvent(
                jdbc, "after-boundary", LocalDate.of(2026, 7, 1),
                new BigDecimal("1.00"));

        String snapshotsBefore = TransportTestFixtures.tableFingerprint(
                jdbc, "pillar_snapshot", "pillar, snapshot_date, id");
        String nodesBefore = TransportTestFixtures.tableFingerprint(
                jdbc, "capability_node", "id");
        String eventsBefore = TransportTestFixtures.tableFingerprint(
                jdbc, "event", "id");

        TransportCoherenceReport result = service.runForQuarter(PERIOD);

        assertEquals("DIVERGENT", result.state());
        assertEquals("B_AHEAD", result.polarity());
        assertEquals(2, result.consecutiveQuarterStreak());
        assertEquals(List.of(b, c, a, d, e, f, g, h, i, j),
                sampleEventIds(result.id()));
        assertEquals(snapshotsBefore, TransportTestFixtures.tableFingerprint(
                jdbc, "pillar_snapshot", "pillar, snapshot_date, id"));
        assertEquals(nodesBefore, TransportTestFixtures.tableFingerprint(
                jdbc, "capability_node", "id"));
        assertEquals(eventsBefore, TransportTestFixtures.tableFingerprint(
                jdbc, "event", "id"));

        event("late-high-impact", "2026-06-15", "1.00");
        TransportCoherenceReport rerun = service.runForQuarter(PERIOD);
        assertEquals(result.id(), rerun.id());
        assertEquals(List.of(b, c, a, d, e, f, g, h, i, j),
                sampleEventIds(result.id()));
        assertEquals(10, transportRepository.findSamples(result.id()).size());
    }

    @Test
    void missingInputsPersistAnHonestInsufficientReportWithoutSamples() {
        TransportCoherenceReport result = service.runForQuarter(
                LocalDate.of(2026, 3, 31));

        assertEquals("INSUFFICIENT_DATA", result.state());
        assertFalse(result.alertActive());
        assertEquals(0, transportRepository.findSamples(result.id()).size());
        assertEquals(result.id(), transportRepository
                .findLatestCoherenceReport().orElseThrow().id());
    }

    private long event(String suffix, String occurredOn, String impact) {
        return TransportTestFixtures.insertConfirmedPillarOneEvent(
                jdbc, suffix, LocalDate.parse(occurredOn), new BigDecimal(impact));
    }

    private List<Long> sampleEventIds(long reportId) {
        return new ArrayList<>(transportRepository.findSamples(reportId).stream()
                .map(TransportCoherenceSample::eventId)
                .toList());
    }

    private static SnapshotRow snapshot(double trendUsed) {
        return new SnapshotRow(
                0, 1, PERIOD, 0.40, -0.4, trendUsed, trendUsed,
                4, 10, 2098.4, 2074.2, 2122.6, 2098.4, "eta-v2.10");
    }
}
