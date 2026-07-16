package com.aienterprise.backend.tracker.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WrightProjectionCalculatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 15);
    private static final WrightProjectionCalculator CALCULATOR =
            new WrightProjectionCalculator();

    @Test
    void exactSyntheticPowerLawRecoversAlphaBetaAndRSquared() {
        TransportProjection result = CALCULATOR.calculate(
                AS_OF, assumption(150), exactDecline(5), repeatedCounts(20));

        assertEquals(Math.log(10_000), result.alpha(), 1.0e-7);
        assertEquals(-0.5, result.beta(), 1.0e-7);
        assertEquals(1.0, result.rSquared(), 1.0e-9);
    }

    @Test
    void threePointsProduceVisibleProvisionalEta() {
        TransportProjection result = CALCULATOR.calculate(
                AS_OF, assumption(150), exactDecline(3), repeatedCounts(20));

        assertEquals("PROVISIONAL", result.status());
        assertEquals("PROVISIONAL", result.sufficiencyTier());
        assertEquals(3, result.observationCount());
        assertNotNull(result.centralEtaYear());
        assertFalse(result.centralBeyondHorizon());
    }

    @Test
    void weakFitAddsFlagButKeepsFiniteEta() {
        List<TransportPriceObservation> noisy = List.of(
                observation(2017, 10, new BigDecimal("2000"), "FALCON_9_A"),
                observation(2018, 20, new BigDecimal("10000"), "FALCON_9_B"),
                observation(2019, 40, new BigDecimal("300"), "FALCON_HEAVY_A"));

        TransportProjection result = CALCULATOR.calculate(
                AS_OF, assumption(150), noisy, repeatedCounts(5));

        assertEquals("PROVISIONAL", result.status());
        assertTrue(result.rSquared() < 0.50);
        assertEquals(List.of("WEAK_FIT"), result.qualificationFlags());
        assertNotNull(result.centralEtaYear());
    }

    @Test
    void fivePointsProduceEstablishedTier() {
        TransportProjection result = CALCULATOR.calculate(
                AS_OF, assumption(150), exactDecline(5), repeatedCounts(20));

        assertEquals("ESTABLISHED", result.status());
        assertEquals("ESTABLISHED", result.sufficiencyTier());
        assertEquals(5, result.observationCount());
    }

    @Test
    void nonNegativeBetaProducesNonDecliningWithoutEta() {
        List<TransportPriceObservation> increasing = List.of(
                observation(2017, 10, new BigDecimal("1000"), "FALCON_9_A"),
                observation(2018, 20, new BigDecimal("1200"), "FALCON_9_B"),
                observation(2019, 40, new BigDecimal("1500"), "FALCON_HEAVY_A"));

        TransportProjection result = CALCULATOR.calculate(
                AS_OF, assumption(150), increasing, repeatedCounts(20));

        assertEquals("NON_DECLINING", result.status());
        assertEquals("PROVISIONAL", result.sufficiencyTier());
        assertNull(result.centralEtaYear());
        assertNull(result.centralRequiredLaunches());
        assertFalse(result.centralBeyondHorizon());
    }

    @Test
    void alreadyMetCentralTargetProducesReachedYear() {
        List<TransportPriceObservation> reached = List.of(
                observation(2017, 10, new BigDecimal("800"), "FALCON_9_A"),
                observation(2018, 20, new BigDecimal("400"), "FALCON_9_B"),
                observation(2019, 40, new BigDecimal("180"), "FALCON_HEAVY_A"));

        TransportProjection result = CALCULATOR.calculate(
                AS_OF, assumption(150), reached, repeatedCounts(20));

        assertEquals("REACHED", result.status());
        assertEquals(2019.0, result.centralEtaYear());
        assertEquals(2018.0, result.earliestEtaYear());
    }

    @Test
    void eachSensitivityEdgeCrossesTheHorizonIndependently() {
        TransportProjection centralAndLatestBeyond = CALCULATOR.calculate(
                AS_OF, assumption(20), exactDecline(5), repeatedCounts(20));

        assertFalse(centralAndLatestBeyond.earliestBeyondHorizon());
        assertNotNull(centralAndLatestBeyond.earliestEtaYear());
        assertTrue(centralAndLatestBeyond.centralBeyondHorizon());
        assertNull(centralAndLatestBeyond.centralEtaYear());
        assertNotNull(centralAndLatestBeyond.centralRequiredLaunches());
        assertTrue(centralAndLatestBeyond.latestBeyondHorizon());
        assertNull(centralAndLatestBeyond.latestEtaYear());
        assertNotNull(centralAndLatestBeyond.hardRequiredLaunches());
        assertEquals("BEYOND_HORIZON", centralAndLatestBeyond.status());

        TransportProjection latestOnlyBeyond = CALCULATOR.calculate(
                AS_OF, assumption(20), exactDecline(5), repeatedCounts(200));

        assertFalse(latestOnlyBeyond.earliestBeyondHorizon());
        assertFalse(latestOnlyBeyond.centralBeyondHorizon());
        assertNotNull(latestOnlyBeyond.centralEtaYear());
        assertTrue(latestOnlyBeyond.latestBeyondHorizon());
        assertNull(latestOnlyBeyond.latestEtaYear());
        assertEquals("ESTABLISHED", latestOnlyBeyond.status());
    }

    @Test
    void cadenceUsesFiveYearsCentrallyAndThreeWhenSparse() {
        TransportProjection complete = CALCULATOR.calculate(
                AS_OF, assumption(150), exactDecline(3),
                annualCounts(2015, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10));

        assertEquals(8.0, complete.centralCadence(), 1.0e-9);
        assertEquals(9.0, complete.fastCadence(), 1.0e-9);
        assertEquals(5.5, complete.slowCadence(), 1.0e-9);

        TransportProjection sparse = CALCULATOR.calculate(
                AS_OF, assumption(150), exactDecline(3),
                annualCounts(2021, 20, 20, 20, 20));

        assertEquals(20.0, sparse.centralCadence(), 1.0e-9);
        assertEquals(20.0, sparse.fastCadence(), 1.0e-9);
        assertEquals(20.0, sparse.slowCadence(), 1.0e-9);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void zeroOrNonFiniteInputsFailClosedAsInsufficient(InvalidCase invalid) {
        TransportProjection result = CALCULATOR.calculate(
                AS_OF, assumption(150), invalid.observations(), invalid.counts());

        assertEquals("INSUFFICIENT_DATA", result.status());
        assertEquals("INSUFFICIENT_DATA", result.sufficiencyTier());
        assertNull(result.centralEtaYear());
    }

    private static Stream<InvalidCase> invalidCases() {
        List<TransportPriceObservation> zeroPrice = new ArrayList<>(exactDecline(3));
        zeroPrice.set(1, observation(
                2018, 20, BigDecimal.ZERO, "FALCON_9_ZERO"));

        List<TransportPriceObservation> nonFinite = new ArrayList<>(exactDecline(3));
        nonFinite.set(1, observation(
                2018, 20, new BigDecimal("1e10000"), "FALCON_9_HUGE"));

        List<TransportPriceObservation> duplicateCumulative = List.of(
                observation(2017, 10, new BigDecimal("1000"), "FALCON_9_A"),
                observation(2018, 10, new BigDecimal("900"), "FALCON_9_B"),
                observation(2019, 40, new BigDecimal("800"), "FALCON_HEAVY_A"));

        return Stream.of(
                new InvalidCase("zero price", zeroPrice, repeatedCounts(20)),
                new InvalidCase("non-finite double conversion", nonFinite,
                        repeatedCounts(20)),
                new InvalidCase("non-increasing cumulative launches", duplicateCumulative,
                        repeatedCounts(20)),
                new InvalidCase("zero cadence", exactDecline(3),
                        annualCounts(2020, 0, 0, 0, 0, 0)));
    }

    private static TransportAssumption assumption(int horizonYears) {
        return new TransportAssumption(
                "transport-assumptions-v1", "wright-falcon-v1",
                new BigDecimal("200"), new BigDecimal("500"),
                new BigDecimal("100"), 2025, horizonYears,
                new BigDecimal("0.50"), new BigDecimal("1.50"));
    }

    private static List<TransportPriceObservation> exactDecline(int size) {
        long[] cumulative = {10, 20, 40, 80, 160};
        List<TransportPriceObservation> observations = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            BigDecimal price = BigDecimal.valueOf(
                    10_000.0 / Math.sqrt(cumulative[index]))
                    .setScale(8, RoundingMode.HALF_UP);
            observations.add(observation(
                    2017 + index, cumulative[index], price,
                    "FALCON_9_" + (char) ('A' + index)));
        }
        return observations;
    }

    private static TransportPriceObservation observation(
            int year, long cumulative, BigDecimal realPrice, String variant) {
        return new TransportPriceObservation(
                0, year, "FALCON", variant,
                new BigDecimal("62000000"), new BigDecimal("22800"),
                realPrice, BigDecimal.ONE, BigDecimal.ONE, realPrice,
                cumulative, "Test source", "https://example.test/source",
                "numeric test fixture", AS_OF, "a".repeat(64),
                "Synthetic numeric observation.");
    }

    private static List<AnnualLaunchCount> repeatedCounts(long count) {
        return annualCounts(2015,
                count, count, count, count, count,
                count, count, count, count, count);
    }

    private static List<AnnualLaunchCount> annualCounts(int startYear, long... counts) {
        List<AnnualLaunchCount> result = new ArrayList<>();
        for (int index = 0; index < counts.length; index++) {
            result.add(new AnnualLaunchCount(startYear + index, counts[index]));
        }
        return result;
    }

    private record InvalidCase(
            String name,
            List<TransportPriceObservation> observations,
            List<AnnualLaunchCount> counts) {

        @Override
        public String toString() {
            return name;
        }
    }
}
