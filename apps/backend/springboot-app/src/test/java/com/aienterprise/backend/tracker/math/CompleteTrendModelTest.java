package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

class CompleteTrendModelTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 1);

    @Test
    void shrinkageHandlesZeroDenseAndNegativePriorWithoutPositiveFloor() {
        assertEquals(0.02, CompleteTrendModel.shrink(0.10, 0, 0.02, 4), 1e-12);
        assertEquals((100 * 0.10 + 4 * 0.02) / 104,
                CompleteTrendModel.shrink(0.10, 100, 0.02, 4), 1e-12);
        assertEquals(-0.03,
                CompleteTrendModel.shrink(0.10, 0, -0.03, 4), 1e-12);
    }

    @Test
    void computesOneFiniteCutoffSafePriorAcrossSixPillars() {
        Map<Integer, Double> readiness = new LinkedHashMap<>();
        Map<Integer, List<SnapshotRow>> histories = new LinkedHashMap<>();
        Map<Integer, List<StateChangeEvent>> changes = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            readiness.put(pillar, 0.30 + pillar * 0.01);
            histories.put(pillar, risingHistory(pillar, pillar * 0.01));
            changes.put(pillar, List.of(
                    event(pillar, 1, "2018-01-01"),
                    event(pillar, 2, "2020-01-01"),
                    event(pillar, 3, "2022-01-01"),
                    event(pillar, 4, "2024-01-01")));
        }

        CompleteTrendModel.Result result = new CompleteTrendModel().calculate(
                readiness, histories, changes, Map.of(),
                Params.defaults(), AS_OF, 0.85);

        double expectedPrior = result.pillars().values().stream()
                .map(PillarTrendResult::trendFit)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average().orElseThrow();
        assertEquals(expectedPrior, result.priorSlope(), 1e-12);
        assertEquals(6, result.pillars().size());
        result.pillars().values().forEach(fit -> {
            assertEquals(4, fit.eventsInWindow());
            assertEquals(12, fit.windowYears());
            assertTrue(Double.isFinite(fit.trendUsed()));
            assertTrue(Double.isFinite(fit.slopeStandardError()));
            assertTrue(fit.slopeStandardError() >= 0);
        });
    }

    @Test
    void negativeUsedSlopeLeavesEtaUnresolved() {
        Map<Integer, Double> readiness = new LinkedHashMap<>();
        Map<Integer, List<SnapshotRow>> histories = new LinkedHashMap<>();
        Map<Integer, List<StateChangeEvent>> changes = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            readiness.put(pillar, 0.30);
            histories.put(pillar, fallingHistory(pillar));
            changes.put(pillar, List.of());
        }

        CompleteTrendModel.Result result = new CompleteTrendModel().calculate(
                readiness, histories, changes, Map.of(),
                Params.defaults(), AS_OF, 0.85);

        PillarTrendResult first = result.pillars().get(1);
        assertTrue(first.trendUsed() < 0);
        assertNull(first.etaYear());
    }

    private static List<SnapshotRow> risingHistory(int pillar, double yearlyGain) {
        List<SnapshotRow> rows = new ArrayList<>();
        for (int year = 2016; year <= 2025; year++) {
            double readiness = 0.15 + yearlyGain * (year - 2016);
            rows.add(snapshot(pillar, year, readiness));
        }
        return rows;
    }

    private static List<SnapshotRow> fallingHistory(int pillar) {
        List<SnapshotRow> rows = new ArrayList<>();
        for (int year = 2016; year <= 2025; year++) {
            rows.add(snapshot(pillar, year, 0.50 - 0.02 * (year - 2016)));
        }
        return rows;
    }

    private static SnapshotRow snapshot(int pillar, int year, double readiness) {
        return new SnapshotRow(
                0, pillar, LocalDate.of(year, 1, 1), readiness,
                LogitEta.logitClipped(readiness, Params.defaults().epsilon()),
                null, null, null, null, null, null, null, null,
                "params-v2", readiness, "graph-v1.0");
    }

    private static StateChangeEvent event(
            int pillar, long id, String date) {
        return new StateChangeEvent(
                id, pillar, LocalDate.parse(date), "FLIGHT_TEST",
                1, 2, "ACTIVE", "ACTIVE");
    }
}
