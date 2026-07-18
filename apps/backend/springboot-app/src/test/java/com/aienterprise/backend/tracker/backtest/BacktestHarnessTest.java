package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.backfill.BackfillClaim;
import com.aienterprise.backend.tracker.backfill.BackfillReview;
import com.aienterprise.backend.tracker.backfill.ProgramEndEffect;
import com.aienterprise.backend.tracker.graph.CapabilityEdgeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;

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
        assertEquals("backtest-report-v2", report.reportVersion());
        assertEquals("backtest-candidates-v2", report.candidateRegistryVersion());
        assertEquals(Set.of(
                        BacktestReport.ModelRole.SELECTED,
                        BacktestReport.ModelRole.ACTIVE,
                        BacktestReport.ModelRole.PERSISTENCE,
                        BacktestReport.ModelRole.ALWAYS_NO_CHANGE),
                report.modelEvaluations().stream()
                        .map(BacktestReport.ModelEvaluation::role)
                        .collect(Collectors.toSet()));

        BacktestReport.ModelEvaluation persistence = evaluation(
                report, BacktestReport.ModelRole.PERSISTENCE);
        BacktestReport.MetricComparison persistenceMae = persistence.metrics().stream()
                .filter(metric -> metric.code() == BacktestMetric.Code.READINESS_MAE)
                .filter(metric -> metric.pillar() == 0)
                .findFirst().orElseThrow();
        assertEquals(.04, persistenceMae.calibrationValue(), 1e-12);
        assertEquals(.04, persistenceMae.holdoutValue(), 1e-12);

        BacktestReport.ModelEvaluation alwaysNoChange = evaluation(
                report, BacktestReport.ModelRole.ALWAYS_NO_CHANGE);
        BacktestReport.MetricComparison direction = alwaysNoChange.metrics().stream()
                .filter(metric -> metric.code() == BacktestMetric.Code.DIRECTION_ACCURACY)
                .filter(metric -> metric.pillar() == 0)
                .findFirst().orElseThrow();
        assertEquals(0.0, direction.holdoutValue(), 1e-12);
    }

    @Test
    void evaluatesActiveAndSelectedCandidatesSeparatelyWithoutPromotion() {
        BacktestCandidate recommended = new BacktestCandidate(8, 4, .75);
        List<String> calls = new ArrayList<>();
        BacktestHarness.EvaluatorFactory factory = (input, fingerprint) ->
                (candidate, folds) -> {
                    BacktestSchedule.Cohort cohort = folds.getFirst().cohort();
                    calls.add(cohort + ":" + candidate.id());
                    double mae = candidate.equals(recommended) ? .01 : .20;
                    return new BacktestHarness.CandidateEvaluation(
                            candidate, syntheticFolds(folds),
                            CalibrationSelectorTest.overallBundle(
                                    mae, .20, .70, .80, 10.0, false));
                };
        BacktestHarness harness = new BacktestHarness(
                factory, new CalibrationSelector(.01));

        BacktestReport report = harness.run(BacktestTestFixtures.input(
                LocalDate.of(2011, 1, 3)));

        assertEquals(recommended, report.selectedCandidate());
        assertEquals(29, calls.size());
        assertEquals("HOLDOUT:" + recommended.id(), calls.get(27));
        assertEquals("HOLDOUT:" + BacktestCandidate.DEFAULT.id(), calls.get(28));
        assertEquals(recommended, evaluation(
                report, BacktestReport.ModelRole.SELECTED).candidate());
        assertEquals(BacktestCandidate.DEFAULT, evaluation(
                report, BacktestReport.ModelRole.ACTIVE).candidate());
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
        assertEquals(4, first.modelEvaluations().size());
    }

    @Test
    void targetTruthIsReadFromTheSameCandidateGraphHistory() {
        CapabilityGraph graph = graphWithDependency(.20);
        HistoricalClaimReplay replay = new HistoricalClaimReplay(
                BacktestTestFixtures.nodes(), List.of(
                        claim("P1-L9", "P1-A", 9),
                        claim("P2-L1", "P2-A", 1)));
        LocalDate target = LocalDate.of(2011, 1, 3);
        CapabilityGraph candidateGraph = new BacktestCandidate(6, 4, .75)
                .apply(graph);
        Map<Integer, List<com.aienterprise.backend.tracker.domain.SnapshotRow>>
                centralHistory = replay.weeklyHistory(
                        target, BacktestTestFixtures.model().params(), graph);
        Map<Integer, List<com.aienterprise.backend.tracker.domain.SnapshotRow>>
                candidateHistory = replay.weeklyHistory(
                        target, BacktestTestFixtures.model().params(), candidateGraph);

        double central = BacktestHarness.targetSnapshot(
                centralHistory, 1, target).readiness();
        double candidate = BacktestHarness.targetSnapshot(
                candidateHistory, 1, target).readiness();

        assertTrue(Math.abs(central - candidate) > 1e-6);
        assertEquals(.23, central, 1e-12);
        assertEquals(.18, candidate, 1e-12);
    }

    private static BacktestReport.ModelEvaluation evaluation(
            BacktestReport report, BacktestReport.ModelRole role) {
        return report.modelEvaluations().stream()
                .filter(value -> value.role() == role)
                .findFirst().orElseThrow();
    }

    private static CapabilityGraph graphWithDependency(double delta) {
        List<CapabilityEdgeRow> edges = List.of(new CapabilityEdgeRow(
                "graph-v1.0", "P2-A", "P1-A", 1, delta));
        CapabilityGraph draft = new CapabilityGraph(
                "graph-v1.0", "nodes-v1.0", "0".repeat(64), edges.size(), edges);
        return new CapabilityGraph(
                draft.version(), draft.nodeSetVersion(), draft.computedSha256(),
                draft.declaredEdgeCount(), draft.edges());
    }

    private static BackfillClaim claim(String id, String nodeCode, int level) {
        return new BackfillClaim(
                id, "candidate-" + id, "nodes-v1.0", "r2.0", nodeCode,
                "FLIGHT_TEST", level, "fixture", LocalDate.of(1958, 1, 1),
                "DAY", "OFFICIAL", id, "fixture", ProgramEndEffect.NONE,
                null, List.of(), new BackfillReview("APPROVED", "APPROVED", null));
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
