package com.aienterprise.backend.tracker.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

class TransportCoherenceCalculatorTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 6, 30);
    private static final TransportCoherenceCalculator CALCULATOR =
            new TransportCoherenceCalculator();

    @ParameterizedTest
    @MethodSource("priceDirections")
    void priceDirectionUsesDeclaredBetaThreshold(double beta, String expected) {
        TransportCoherenceReport result = calculate(
                projection(beta), flatCounts(), snapshot(0.0), null);

        assertEquals(expected, result.priceDirection());
    }

    private static Stream<Arguments> priceDirections() {
        return Stream.of(
                Arguments.of(-0.2, "ADVANCING"),
                Arguments.of(-0.000001, "FLAT"),
                Arguments.of(0.0, "FLAT"),
                Arguments.of(0.000001, "FLAT"),
                Arguments.of(0.2, "REGRESSING"));
    }

    @ParameterizedTest
    @MethodSource("cadenceDirections")
    void cadenceDirectionUsesLogSlope(long[] launches, String expected) {
        TransportCoherenceReport result = calculate(
                projection(0.0), counts(launches), snapshot(0.0), null);

        assertEquals(expected, result.cadenceDirection());
    }

    private static Stream<Arguments> cadenceDirections() {
        return Stream.of(
                Arguments.of(new long[] {1, 2, 4, 8, 16}, "ADVANCING"),
                Arguments.of(new long[] {16, 8, 4, 2, 1}, "REGRESSING"),
                Arguments.of(new long[] {10, 10, 10, 10, 10}, "FLAT"));
    }

    @Test
    void opposedLayerBInputsBecomeMixedAndCannotAlert() {
        TransportCoherenceReport result = calculate(
                projection(-0.2), counts(16, 8, 4, 2, 1), snapshot(0.2), null);

        assertEquals("MIXED", result.layerBDirection());
        assertEquals("MIXED", result.state());
        assertEquals("NONE", result.polarity());
        assertEquals(0, result.consecutiveQuarterStreak());
        assertFalse(result.alertActive());
    }

    @Test
    void anyMissingMatchedInputProducesInsufficientData() {
        assertEquals("INSUFFICIENT_DATA", calculate(
                null, flatCounts(), snapshot(0.0), null).state());
        assertEquals("INSUFFICIENT_DATA", calculate(
                projection(-0.2), counts(1, 2), snapshot(0.0), null).state());
        assertEquals("INSUFFICIENT_DATA", calculate(
                projection(-0.2), flatCounts(), null, null).state());
    }

    @Test
    void sameLayerDirectionsAreCoherentAndResetStreak() {
        TransportCoherenceReport previous = watch(
                LocalDate.of(2026, 3, 31), "B_AHEAD");

        TransportCoherenceReport result = calculate(
                projection(-0.2), counts(1, 2, 4, 8, 16),
                snapshot(0.2), previous);

        assertEquals("COHERENT", result.state());
        assertEquals("NONE", result.polarity());
        assertEquals(0, result.consecutiveQuarterStreak());
        assertFalse(result.alertActive());
    }

    @Test
    void firstOppositeQuarterCreatesWatch() {
        TransportCoherenceReport result = calculate(
                projection(-0.2), flatCounts(), snapshot(0.0), null);

        assertEquals("WATCH", result.state());
        assertEquals("B_AHEAD", result.polarity());
        assertEquals(1, result.consecutiveQuarterStreak());
        assertFalse(result.alertActive());
        assertEquals(new BigDecimal("1.00"), result.wideningFactor());
    }

    @Test
    void samePolarityInNextQuarterBecomesDivergent() {
        TransportCoherenceReport result = calculate(
                projection(-0.2), flatCounts(), snapshot(0.0),
                watch(LocalDate.of(2026, 3, 31), "B_AHEAD"));

        assertEquals("DIVERGENT", result.state());
        assertEquals(2, result.consecutiveQuarterStreak());
        assertTrue(result.alertActive());
        assertEquals(new BigDecimal("1.50"), result.wideningFactor());
        assertEquals(PERIOD, result.firstDivergentPeriod());
    }

    @Test
    void continuingDivergencePreservesFirstDivergentPeriod() {
        LocalDate firstDivergent = LocalDate.of(2026, 3, 31);
        TransportCoherenceReport previous = new TransportCoherenceReport(
                0, firstDivergent, firstDivergent,
                "ADVANCING", "ADVANCING", "ADVANCING", "FLAT",
                "DIVERGENT", "B_AHEAD", 2, true,
                new BigDecimal("1.50"), firstDivergent);

        TransportCoherenceReport result = calculate(
                projection(-0.2), flatCounts(), snapshot(0.0), previous);

        assertEquals("DIVERGENT", result.state());
        assertEquals(3, result.consecutiveQuarterStreak());
        assertEquals(firstDivergent, result.firstDivergentPeriod());
    }

    @Test
    void quarterGapOrPolarityChangeRestartsWatchAtOne() {
        TransportCoherenceReport gap = calculate(
                projection(-0.2), flatCounts(), snapshot(0.0),
                watch(LocalDate.of(2025, 12, 31), "B_AHEAD"));
        TransportCoherenceReport changedPolarity = calculate(
                projection(-0.2), flatCounts(), snapshot(0.0),
                watch(LocalDate.of(2026, 3, 31), "C_AHEAD"));

        assertEquals("WATCH", gap.state());
        assertEquals(1, gap.consecutiveQuarterStreak());
        assertEquals("WATCH", changedPolarity.state());
        assertEquals(1, changedPolarity.consecutiveQuarterStreak());
    }

    @Test
    void mixedOrInsufficientSuccessorResetsPriorWatch() {
        TransportCoherenceReport previous = watch(
                LocalDate.of(2026, 3, 31), "B_AHEAD");
        TransportCoherenceReport mixed = calculate(
                projection(-0.2), counts(16, 8, 4, 2, 1),
                snapshot(0.0), previous);
        TransportCoherenceReport insufficient = calculate(
                null, flatCounts(), snapshot(0.0), previous);

        assertEquals(0, mixed.consecutiveQuarterStreak());
        assertFalse(mixed.alertActive());
        assertNull(mixed.firstDivergentPeriod());
        assertEquals(0, insufficient.consecutiveQuarterStreak());
        assertFalse(insufficient.alertActive());
    }

    private static TransportCoherenceReport calculate(
            TransportProjection projection,
            List<AnnualLaunchCount> counts,
            SnapshotRow snapshot,
            TransportCoherenceReport previous) {
        return CALCULATOR.calculate(PERIOD, projection, counts, snapshot, previous);
    }

    private static TransportProjection projection(double beta) {
        TransportProjection base = TransportTestFixtures.projection(PERIOD);
        return new TransportProjection(
                base.id(), base.asOfDate(), base.assumptionVersion(), base.modelVersion(),
                base.status(), base.sufficiencyTier(), base.qualificationFlags(),
                base.observationCount(), base.alpha(), beta, base.rSquared(),
                base.currentCumulativeLaunches(), base.centralCadence(),
                base.fastCadence(), base.slowCadence(),
                base.centralTargetUsdPerKg(), base.easyTargetUsdPerKg(),
                base.hardTargetUsdPerKg(), base.centralRequiredLaunches(),
                base.easyRequiredLaunches(), base.hardRequiredLaunches(),
                base.centralEtaYear(), base.earliestEtaYear(), base.latestEtaYear(),
                base.centralBeyondHorizon(), base.earliestBeyondHorizon(),
                base.latestBeyondHorizon(), base.priceBasisYear(), base.horizonYears(),
                base.intervalKind(), base.basis(), base.priceMeaning(),
                base.projectionLabel(), base.reasonCode());
    }

    private static SnapshotRow snapshot(Double trendUsed) {
        return new SnapshotRow(
                0, 1, PERIOD, 0.40, -0.4, trendUsed, trendUsed,
                4, 10, 2098.4, 2074.2, 2122.6, 2098.4, "eta-v2.10");
    }

    private static List<AnnualLaunchCount> flatCounts() {
        return counts(10, 10, 10, 10, 10);
    }

    private static List<AnnualLaunchCount> counts(long... launches) {
        List<AnnualLaunchCount> result = new ArrayList<>();
        for (int index = 0; index < launches.length; index++) {
            result.add(new AnnualLaunchCount(2020 + index, launches[index]));
        }
        return result;
    }

    private static TransportCoherenceReport watch(LocalDate period, String polarity) {
        return new TransportCoherenceReport(
                0, period, period,
                "ADVANCING", "FLAT", "ADVANCING", "FLAT",
                "WATCH", polarity, 1, false, new BigDecimal("1.00"), null);
    }
}
