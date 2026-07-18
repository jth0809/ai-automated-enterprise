package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class BacktestSkillDiagnosticsTest {

    @Test
    void reportsNoSkillWhenSelectedMaeIsWorseThanPersistence() {
        BacktestReport report = withMae(.012, .0075);

        BacktestSkillDiagnostics.Result result = BacktestSkillDiagnostics.from(
                report, BacktestCandidate.DEFAULT);

        assertEquals(BacktestSkillDiagnostics.SkillStatus.NO_SKILL_VS_PERSISTENCE,
                result.skillStatus());
        assertEquals(1.6, result.readinessMaeRatioVsPersistence(), 1e-12);
        assertTrue(result.selectedMatchesActive());
    }

    @Test
    void distinguishesOutperformanceInsufficientAndLegacyRuns() {
        BacktestSkillDiagnostics.Result outperforms = BacktestSkillDiagnostics.from(
                withMae(.005, .01), BacktestCandidate.DEFAULT);
        assertEquals(BacktestSkillDiagnostics.SkillStatus.OUTPERFORMS_PERSISTENCE,
                outperforms.skillStatus());
        assertEquals(.5, outperforms.readinessMaeRatioVsPersistence(), 1e-12);

        BacktestSkillDiagnostics.Result insufficient = BacktestSkillDiagnostics.from(
                withMae(.005, 0.0), BacktestCandidate.DEFAULT);
        assertEquals(BacktestSkillDiagnostics.SkillStatus.INSUFFICIENT_DATA,
                insufficient.skillStatus());
        assertEquals(null, insufficient.readinessMaeRatioVsPersistence());

        BacktestReport current = BacktestTestFixtures.report();
        BacktestReport legacy = copy(current, "backtest-report-v1", List.of());
        BacktestSkillDiagnostics.Result legacyResult = BacktestSkillDiagnostics.from(
                legacy, BacktestCandidate.DEFAULT);
        assertEquals(BacktestSkillDiagnostics.SkillStatus.LEGACY_NOT_EVALUATED,
                legacyResult.skillStatus());
    }

    private static BacktestReport withMae(double selected, double persistence) {
        BacktestReport current = BacktestTestFixtures.report();
        BacktestReport.MetricComparison selectedMetric = metric(selected);
        BacktestReport.MetricComparison persistenceMetric = metric(persistence);
        return copy(current, BacktestReport.REPORT_VERSION, List.of(
                new BacktestReport.ModelEvaluation(
                        BacktestReport.ModelRole.SELECTED,
                        current.selectedCandidate(), List.of(selectedMetric)),
                new BacktestReport.ModelEvaluation(
                        BacktestReport.ModelRole.PERSISTENCE,
                        null, List.of(persistenceMetric))));
    }

    private static BacktestReport.MetricComparison metric(double holdout) {
        return new BacktestReport.MetricComparison(
                BacktestMetric.Code.READINESS_MAE, 0,
                holdout, holdout, 6, 6,
                BacktestMetric.Status.OK, BacktestMetric.Status.OK);
    }

    private static BacktestReport copy(
            BacktestReport source,
            String reportVersion,
            List<BacktestReport.ModelEvaluation> evaluations) {
        return new BacktestReport(
                reportVersion, source.inputSha256(), source.seed(),
                source.datasetSha256(), source.nodeSetVersion(),
                source.rubricVersion(), source.paramsVersion(),
                source.graphVersion(), source.candidateRegistryVersion(),
                source.asOf(), source.calibrationStart(), source.calibrationEnd(),
                source.holdoutStart(), source.holdoutEnd(), source.horizonWeeks(),
                source.sampleCount(), source.calibrationCutoffCount(),
                source.holdoutCutoffCount(), source.selectedCandidate(),
                source.objectiveScore(), source.calibrationCandidates(),
                source.folds(), source.metrics(), evaluations);
    }
}
