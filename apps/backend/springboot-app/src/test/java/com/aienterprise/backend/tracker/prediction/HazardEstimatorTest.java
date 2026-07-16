package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.backfill.BackfillClaim;
import com.aienterprise.backend.tracker.backfill.BackfillReview;
import com.aienterprise.backend.tracker.backfill.ProgramEndEffect;
import com.aienterprise.backend.tracker.domain.NodeRow;

class HazardEstimatorTest {

    private static final LocalDate START = LocalDate.of(1957, 1, 7);

    @Test
    void countsVerifiedAdvanceEventsNotLevelsAndExcludesDormancyExposure() {
        HazardEstimator.Result result = new HazardEstimator().estimate(
                List.of(node(1, "P1-A", 1)),
                List.of(
                        claim("A", "P1-A", "FLIGHT_TEST", 3,
                                LocalDate.of(1958, 1, 7), ProgramEndEffect.NONE),
                        claim("B", "P1-A", "ROLLBACK", 1,
                                LocalDate.of(1959, 1, 7), ProgramEndEffect.NONE),
                        claim("C", "P1-A", "FLIGHT_TEST", 2,
                                LocalDate.of(1960, 1, 7), ProgramEndEffect.NONE),
                        claim("D", "P1-A", "PROGRAM_CANCELLATION", null,
                                LocalDate.of(1960, 1, 7),
                                ProgramEndEffect.CAPABILITY_PROGRAM_END)),
                LocalDate.of(1963, 1, 7), 2, HazardParameters.defaults());

        HazardEstimator.NodeHazard hazard = result.nodes().getFirst();
        assertEquals(2, hazard.advances());
        assertEquals(days(START, LocalDate.of(1962, 1, 7)),
                hazard.exposureYears(), 1e-12);
        assertEquals(2, hazard.currentLevel());
        assertEquals("DORMANT", hazard.currentStatus());
        assertFalse(hazard.eligible());
    }

    @Test
    void aLaterVerifiedAdvanceRestoresExposureAndEligibility() {
        HazardEstimator.Result result = new HazardEstimator().estimate(
                List.of(node(1, "P1-A", 1)),
                List.of(
                        claim("A", "P1-A", "FLIGHT_TEST", 2,
                                LocalDate.of(1958, 1, 7), ProgramEndEffect.NONE),
                        claim("B", "P1-A", "PROGRAM_CANCELLATION", null,
                                LocalDate.of(1958, 1, 7),
                                ProgramEndEffect.CAPABILITY_PROGRAM_END),
                        claim("C", "P1-A", "FLIGHT_TEST", 3,
                                LocalDate.of(1961, 7, 7), ProgramEndEffect.NONE)),
                LocalDate.of(1962, 1, 7), 2, HazardParameters.defaults());

        HazardEstimator.NodeHazard hazard = result.nodes().getFirst();
        double expected = days(START, LocalDate.of(1960, 1, 7))
                + days(LocalDate.of(1961, 7, 7), LocalDate.of(1962, 1, 7));
        assertEquals(expected, hazard.exposureYears(), 1e-12);
        assertEquals(2, hazard.advances());
        assertEquals("ACTIVE", hazard.currentStatus());
        assertTrue(hazard.eligible());
    }

    @Test
    void sparseNodesBorrowOnlyThePillarRateAndFutureClaimsCannotLeak() {
        List<NodeRow> nodes = List.of(
                node(1, "P2-A", 2), node(2, "P2-B", 2));
        HazardEstimator.Result result = new HazardEstimator().estimate(
                nodes,
                List.of(
                        claim("A", "P2-A", "FLIGHT_TEST", 4,
                                LocalDate.of(1958, 1, 7), ProgramEndEffect.NONE),
                        claim("FUTURE", "P2-B", "FLIGHT_TEST", 8,
                                LocalDate.of(1970, 1, 7), ProgramEndEffect.NONE)),
                LocalDate.of(1961, 1, 7), 15, HazardParameters.defaults());

        HazardEstimator.NodeHazard a = result.byCode().get("P2-A");
        HazardEstimator.NodeHazard b = result.byCode().get("P2-B");
        double pooled = (a.advances() + b.advances())
                / (a.exposureYears() + b.exposureYears());
        assertEquals(pooled, a.pillarRate(), 1e-12);
        assertEquals((1 + 4 * pooled) / (a.exposureYears() + 4),
                a.nodeRate(), 1e-12);
        assertEquals((4 * pooled) / (b.exposureYears() + 4),
                b.nodeRate(), 1e-12);
        assertEquals(0, b.currentLevel());
        assertTrue(b.nodeRate() > 0);
    }

    @Test
    void probabilityIsFiniteAndUsesTheDeclaredBounds() {
        HazardParameters parameters = HazardParameters.defaults();
        assertEquals(0.02,
                HazardEstimator.boundedProbability(0, 2.0, parameters), 0);
        assertEquals(0.98,
                HazardEstimator.boundedProbability(100, 2.0, parameters), 0);
        assertEquals(1 - Math.exp(-0.25),
                HazardEstimator.rawProbability(0.5, 0.5), 1e-12);
    }

    private static double days(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) / 365.2425;
    }

    private static NodeRow node(long id, String code, int pillar) {
        return new NodeRow(
                id, code, pillar, code, pillar == 6 ? "EGL" : "TRL", 0,
                null, "ACTIVE", null, null, 0.5, false,
                "fixture", "nodes-v1.0");
    }

    private static BackfillClaim claim(
            String id,
            String nodeCode,
            String eventType,
            Integer level,
            LocalDate occurredOn,
            ProgramEndEffect effect) {
        return new BackfillClaim(
                "BF-" + id, "HC-" + id, "nodes-v1.0", "r2.0",
                nodeCode, eventType, level, "Actor", occurredOn,
                "DAY", "OFFICIAL", "Event " + id, "Fixture.", effect,
                effect == ProgramEndEffect.CAPABILITY_PROGRAM_END
                        ? "Representative lineage ended." : null,
                List.of("HC-" + id + "#SRC"),
                new BackfillReview("APPROVED", "APPROVED", "Reviewed."));
    }
}
