package com.aienterprise.backend.tracker.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TransportEconomicsRepositoryTest {

    @Autowired
    private TransportEconomicsRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void roundTripsAssumptionObservationProjectionAndReport() {
        TransportAssumption assumption = TransportTestFixtures.assumption();
        repository.insertAssumption(assumption);
        repository.insertObservation(TransportTestFixtures.observation(2018, 68));
        repository.saveProjection(
                TransportTestFixtures.projection(LocalDate.of(2026, 7, 15)));
        repository.saveCoherenceReport(
                TransportTestFixtures.watchReport(LocalDate.of(2026, 6, 30)));

        TransportAssumption storedAssumption = repository
                .findAssumption(assumption.version()).orElseThrow();
        assertEquals(0, assumption.centralTargetUsdPerKg()
                .compareTo(storedAssumption.centralTargetUsdPerKg()));
        assertEquals(1, repository.findPriceObservations().size());
        assertEquals("PROVISIONAL",
                repository.findLatestProjection().orElseThrow().status());
        assertEquals("WATCH",
                repository.findLatestCoherenceReport().orElseThrow().state());
    }

    @Test
    void exactProjectionAndReportRerunsAreIdempotentButChangedEvidenceFails() {
        repository.insertAssumption(TransportTestFixtures.assumption());
        LocalDate asOf = LocalDate.of(2026, 7, 15);
        repository.saveProjection(TransportTestFixtures.projection(asOf));
        repository.saveProjection(TransportTestFixtures.projection(asOf));
        assertEquals(1, count("transport_economics_projection"));
        assertThrows(IllegalStateException.class, () -> repository.saveProjection(
                TransportTestFixtures.projection(asOf, "CHANGED_EVIDENCE")));

        LocalDate period = LocalDate.of(2026, 6, 30);
        repository.saveCoherenceReport(TransportTestFixtures.watchReport(period));
        repository.saveCoherenceReport(TransportTestFixtures.watchReport(period));
        assertEquals(1, count("transport_coherence_report"));
        assertThrows(IllegalStateException.class, () -> repository.saveCoherenceReport(
                TransportTestFixtures.watchReport(period, 2)));
    }

    @Test
    void numericallyEquivalentObservationRerunIsIdempotentAcrossDecimalScales() {
        TransportPriceObservation original = TransportTestFixtures.observation(2018, 68);
        repository.insertObservation(original);
        TransportPriceObservation equivalent = new TransportPriceObservation(
                0, original.observationYear(), original.vehicleFamily(),
                original.vehicleVariant(), normalized(original.publishedPriceUsd()),
                normalized(original.maxLeoPayloadKg()), normalized(original.nominalUsdPerKg()),
                normalized(original.cpiObservationValue()), normalized(original.cpiBasisValue()),
                normalized(original.realBasisUsdPerKg()), original.cumulativeFamilyLaunches(),
                original.sourceLabel(), original.sourceUrl(), original.sourceLocator(),
                original.accessedOn(), original.contentSha256(), original.factSummary());

        repository.insertObservation(equivalent);

        assertEquals(1, count("transport_price_observation"));
    }

    @Test
    void importLedgerRoundTripsAndSampleReviewIsOneWayWithoutTouchingEvent() {
        repository.recordImport("transport-economics-v1", "b".repeat(64), 4, 15, 5);
        assertEquals("b".repeat(64), repository
                .findImportSha("transport-economics-v1").orElseThrow());

        long reportId = repository.saveCoherenceReport(
                TransportTestFixtures.watchReport(LocalDate.of(2026, 6, 30)));
        long eventId = TransportTestFixtures.insertConfirmedPillarOneEvent(jdbc);
        long sampleId = repository.insertSample(reportId, eventId);
        String before = TransportTestFixtures.eventFingerprint(jdbc, eventId);

        assertTrue(repository.reviewSample(sampleId, "검수 완료"));
        assertFalse(repository.reviewSample(sampleId, "두 번째 결정"));
        assertEquals(before, TransportTestFixtures.eventFingerprint(jdbc, eventId));
        TransportCoherenceSample reviewed = repository.findSamples(reportId).getFirst();
        assertEquals("REVIEWED", reviewed.status());
        assertEquals("검수 완료", reviewed.reviewerNote());
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Integer.class)
                .single();
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value.stripTrailingZeros();
    }
}
