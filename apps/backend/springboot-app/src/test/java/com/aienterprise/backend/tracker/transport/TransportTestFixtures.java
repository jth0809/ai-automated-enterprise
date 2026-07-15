package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

final class TransportTestFixtures {

    private TransportTestFixtures() {
    }

    static TransportAssumption assumption() {
        return new TransportAssumption(
                "transport-assumptions-v1", "wright-falcon-v1",
                new BigDecimal("200.0000"), new BigDecimal("500.0000"),
                new BigDecimal("100.0000"), 2025, 150,
                new BigDecimal("0.50000"), new BigDecimal("1.50"));
    }

    static TransportPriceObservation observation(int year, long cumulative) {
        return new TransportPriceObservation(
                0, year, "FALCON", "FALCON_9_EXPENDABLE",
                new BigDecimal("62000000.00"), new BigDecimal("22800.00"),
                new BigDecimal("2719.2982"), new BigDecimal("251.107"),
                new BigDecimal("321.943"), new BigDecimal("3485.8514"),
                cumulative, "NASA Technical Reports Server",
                "https://ntrs.nasa.gov/citations/20180007067",
                "NTRS abstract numeric launch-price statement",
                LocalDate.of(2026, 7, 15), "a".repeat(64),
                "NASA record reports the advertised Falcon 9 price and matching maximum LEO payload.");
    }

    static TransportProjection projection(LocalDate asOfDate) {
        return projection(asOfDate, "FIT_DECLINING");
    }

    static TransportProjection projection(LocalDate asOfDate, String reasonCode) {
        return new TransportProjection(
                0, asOfDate, "transport-assumptions-v1", "wright-falcon-v1",
                "PROVISIONAL", "PROVISIONAL", List.of("WEAK_FIT"), 3,
                9.1, -0.2, 0.42, 500, 80.0, 100.0, 60.0,
                new BigDecimal("200.0000"), new BigDecimal("500.0000"),
                new BigDecimal("100.0000"), 50_000.0, 15_000.0, 120_000.0,
                2098.4, 2074.2, null, false, false, true,
                2025, 150, "ASSUMPTION_SENSITIVITY", "PUBLISHED_PRICE",
                "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD",
                "Declared-assumption scenario; not provider internal cost",
                reasonCode);
    }

    static TransportCoherenceReport watchReport(LocalDate periodEnd) {
        return new TransportCoherenceReport(
                0, periodEnd, periodEnd,
                "ADVANCING", "ADVANCING", "ADVANCING", "FLAT",
                "WATCH", "B_AHEAD", 1, false, new BigDecimal("1.00"), null);
    }

    static TransportCoherenceReport watchReport(LocalDate periodEnd, int streak) {
        return new TransportCoherenceReport(
                0, periodEnd, periodEnd,
                "ADVANCING", "ADVANCING", "ADVANCING", "FLAT",
                "WATCH", "B_AHEAD", streak, false, new BigDecimal("1.00"), null);
    }

    static long insertConfirmedPillarOneEvent(JdbcClient jdbc) {
        Long nodeId = jdbc.sql("""
                SELECT id FROM capability_node WHERE pillar = 1
                ORDER BY id FETCH FIRST 1 ROWS ONLY
                """).query(Long.class).single();
        Long rubricId = jdbc.sql("""
                SELECT id FROM rubric_version ORDER BY id DESC FETCH FIRST 1 ROWS ONLY
                """).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO event
                  (natural_key, node_id, event_type, claimed_level, actor,
                   occurred_on, verification_level, event_status, impact_score,
                   novelty, state_advanced, rubric_version_id)
                VALUES (:key, :nodeId, 'FLIGHT_TEST', 5, 'Test operator',
                        DATE '2026-04-01', 'OFFICIAL', 'CONFIRMED', 0.75,
                        1, 'N', :rubricId)
                """)
                .param("key", "wp33-test-event-" + Instant.now().toEpochMilli())
                .param("nodeId", nodeId)
                .param("rubricId", rubricId)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM event")
                .query(Long.class)
                .single();
    }

    static String eventFingerprint(JdbcClient jdbc, long eventId) {
        return jdbc.sql("""
                SELECT natural_key || '|' || event_status || '|' || state_advanced
                       || '|' || COALESCE(CAST(impact_score AS VARCHAR), '')
                  FROM event WHERE id = :id
                """)
                .param("id", eventId)
                .query(String.class)
                .single();
    }
}
