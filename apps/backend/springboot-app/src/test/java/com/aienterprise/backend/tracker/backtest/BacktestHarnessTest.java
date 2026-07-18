package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BacktestHarnessTest {

    @Test
    void locksCalibrationSelectionBeforeExactlyOneHoldoutEvaluation() {
        List<String> calls = new ArrayList<>();
        BacktestHarness.EvaluatorFactory factory = (input, fingerprint) ->
                (candidate, folds) -> {
                    BacktestSchedule.Cohort cohort = folds.getFirst().cohort();
                    calls.add(cohort + ":" + candidate.id());
                    double mae = candidate.equals(BacktestCandidate.DEFAULT) ? .01 : .20;
                    return new BacktestHarness.CandidateEvaluation(
                            candidate,
                            syntheticFolds(folds),
                            CalibrationSelectorTest.overallBundle(
                                    mae, .20, .70, .80, 10.0, false));
                };
        BacktestHarness harness = new BacktestHarness(
                factory, new CalibrationSelector(.01));

        BacktestReport report = harness.run(BacktestTestFixtures.input(
                LocalDate.of(2011, 1, 3)));

        assertEquals(28, calls.size());
        assertTrue(calls.subList(0, 27).stream().allMatch(value ->
                value.startsWith("CALIBRATION:")));
        assertEquals("HOLDOUT:" + BacktestCandidate.DEFAULT.id(), calls.getLast());
        assertEquals(BacktestCandidate.DEFAULT, report.selectedCandidate());
        assertEquals(27, report.calibrationCandidates().size());
        assertEquals(report.folds().stream()
                        .filter(fold -> fold.cohort() == BacktestSchedule.Cohort.HOLDOUT)
                        .count(),
                report.holdoutCutoffCount() * 6L);
        assertEquals(35, report.metrics().size());
    }

    @Test
    void pureHistoricalEvaluatorIsDeterministicAndRecoversDefaultOnExactTie() {
        BacktestSchedule.Split schedule = new BacktestSchedule.Split(
                new BacktestSchedule.CalibrationFolds(List.of(
                        new BacktestSchedule.Fold(
                                0, BacktestSchedule.Cohort.CALIBRATION,
                                LocalDate.of(2008, 1, 7),
                                LocalDate.of(2009, 1, 5)))),
                new BacktestSchedule.HoldoutFolds(List.of(
                        new BacktestSchedule.Fold(
                                1, BacktestSchedule.Cohort.HOLDOUT,
                                LocalDate.of(2010, 1, 4),
                                LocalDate.of(2011, 1, 3)))));
        var graph = BacktestTestFixtures.graph();
        BacktestHarness.Input input = new BacktestHarness.Input(
                new BacktestFingerprint.Descriptor(
                        "a".repeat(64), "nodes-v1.0", "r2.0", "params-v2",
                        graph.version(), graph.declaredSha256(), 1_000, schedule),
                BacktestTestFixtures.nodes(), List.of(), graph,
                BacktestTestFixtures.model(), java.util.Map.of(), .85);

        BacktestReport first = new BacktestHarness().run(input);
        BacktestReport second = new BacktestHarness().run(input);

        assertEquals(first, second);
        assertEquals(BacktestCandidate.DEFAULT, first.selectedCandidate());
        assertEquals(12, first.folds().size());
        assertEquals(35, first.metrics().size());
    }

    private static List<BacktestReport.FoldResult> syntheticFolds(
            List<BacktestSchedule.Fold> folds) {
        List<BacktestReport.FoldResult> results = new ArrayList<>();
        for (BacktestSchedule.Fold fold : folds) {
            for (int pillar = 1; pillar <= 6; pillar++) {
                results.add(new BacktestReport.FoldResult(
                        fold.index(), fold.cohort(), fold.cutoff(), fold.target(),
                        pillar, .2, .25, .24, -.9, -1.0,
                        true, true, .18, .30, true, 2070.0 + fold.index(),
                        BacktestMetric.Status.OK));
            }
        }
        return results;
    }
}
