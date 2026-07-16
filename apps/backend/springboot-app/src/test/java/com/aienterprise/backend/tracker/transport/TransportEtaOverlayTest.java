package com.aienterprise.backend.tracker.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

class TransportEtaOverlayTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 6, 30);
    private final TransportEtaOverlay overlay = new TransportEtaOverlay();

    @Test
    void activeDivergenceWidensPillarOneBoundsWithoutChangingBase() {
        EtaBounds result = overlay.apply(1,
                snapshot(2100.0, 2080.0, 2120.0), divergent(), 2026);

        assertEquals(2080.0, result.baseEtaLow());
        assertEquals(2120.0, result.baseEtaHigh());
        assertEquals(2070.0, result.etaLow());
        assertEquals(2130.0, result.etaHigh());
        assertTrue(result.coherenceAdjusted());
        assertEquals(PERIOD, result.coherenceReportPeriod());
    }

    @Test
    void wideningClampsEachEdgeToDisplayHorizon() {
        EtaBounds result = overlay.apply(1,
                snapshot(2030.0, 2020.0, 2200.0), divergent(), 2026);

        assertEquals(2026.0, result.etaLow());
        assertEquals(2176.0, result.etaHigh());
    }

    @Test
    void otherPillarWatchOrMissingBaseReturnsUnchangedBounds() {
        EtaBounds otherPillar = overlay.apply(2,
                snapshot(2100.0, 2080.0, 2120.0), divergent(), 2026);
        EtaBounds watch = overlay.apply(1,
                snapshot(2100.0, 2080.0, 2120.0), watch(), 2026);
        EtaBounds missing = overlay.apply(1,
                snapshot(2100.0, null, 2120.0), divergent(), 2026);

        assertUnchanged(otherPillar, 2080.0, 2120.0);
        assertUnchanged(watch, 2080.0, 2120.0);
        assertUnchanged(missing, null, 2120.0);
    }

    private static void assertUnchanged(
            EtaBounds bounds, Double low, Double high) {
        assertEquals(low, bounds.baseEtaLow());
        assertEquals(high, bounds.baseEtaHigh());
        assertEquals(low, bounds.etaLow());
        assertEquals(high, bounds.etaHigh());
        assertFalse(bounds.coherenceAdjusted());
        assertNull(bounds.coherenceReportPeriod());
    }

    private static SnapshotRow snapshot(Double eta, Double low, Double high) {
        return new SnapshotRow(
                0, 1, PERIOD, 0.4, -0.4, 0.1, 0.1,
                4, 10, eta, low, high, eta, "eta-v2.10");
    }

    private static TransportCoherenceReport divergent() {
        return new TransportCoherenceReport(
                0, PERIOD, PERIOD,
                "ADVANCING", "ADVANCING", "ADVANCING", "FLAT",
                "DIVERGENT", "B_AHEAD", 2, true,
                new BigDecimal("1.50"), PERIOD);
    }

    private static TransportCoherenceReport watch() {
        return new TransportCoherenceReport(
                0, PERIOD, PERIOD,
                "ADVANCING", "ADVANCING", "ADVANCING", "FLAT",
                "WATCH", "B_AHEAD", 1, false,
                new BigDecimal("1.00"), null);
    }
}
