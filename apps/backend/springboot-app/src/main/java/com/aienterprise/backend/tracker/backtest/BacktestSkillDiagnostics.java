package com.aienterprise.backend.tracker.backtest;

import java.util.Objects;

/** Honest skill comparison for a completed backtest; never promotes parameters. */
public final class BacktestSkillDiagnostics {

    private static final double ZERO_TOLERANCE = 1e-12;

    private BacktestSkillDiagnostics() {
    }

    public static Result from(
            BacktestReport report, BacktestCandidate activeCandidate) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(activeCandidate, "activeCandidate");
        boolean selectedMatchesActive = report.selectedCandidate()
                .equals(activeCandidate);
        if (!BacktestReport.REPORT_VERSION.equals(report.reportVersion())
                || report.modelEvaluations().isEmpty()) {
            return new Result(
                    SkillStatus.LEGACY_NOT_EVALUATED,
                    null, selectedMatchesActive);
        }

        Double selected = aggregateHoldout(
                report, BacktestReport.ModelRole.SELECTED,
                BacktestMetric.Code.READINESS_MAE);
        Double persistence = aggregateHoldout(
                report, BacktestReport.ModelRole.PERSISTENCE,
                BacktestMetric.Code.READINESS_MAE);
        if (selected == null || persistence == null
                || persistence <= ZERO_TOLERANCE) {
            return new Result(
                    SkillStatus.INSUFFICIENT_DATA,
                    null, selectedMatchesActive);
        }
        double ratio = selected / persistence;
        return new Result(
                ratio < 1.0
                        ? SkillStatus.OUTPERFORMS_PERSISTENCE
                        : SkillStatus.NO_SKILL_VS_PERSISTENCE,
                ratio, selectedMatchesActive);
    }

    private static Double aggregateHoldout(
            BacktestReport report,
            BacktestReport.ModelRole role,
            BacktestMetric.Code code) {
        return report.modelEvaluations().stream()
                .filter(evaluation -> evaluation.role() == role)
                .flatMap(evaluation -> evaluation.metrics().stream())
                .filter(metric -> metric.code() == code && metric.pillar() == 0)
                .filter(metric -> metric.holdoutStatus() == BacktestMetric.Status.OK)
                .map(BacktestReport.MetricComparison::holdoutValue)
                .findFirst().orElse(null);
    }

    public enum SkillStatus {
        OUTPERFORMS_PERSISTENCE,
        NO_SKILL_VS_PERSISTENCE,
        INSUFFICIENT_DATA,
        LEGACY_NOT_EVALUATED
    }

    public record Result(
            SkillStatus skillStatus,
            Double readinessMaeRatioVsPersistence,
            boolean selectedMatchesActive) {

        public Result {
            skillStatus = Objects.requireNonNull(skillStatus, "skillStatus");
            if (readinessMaeRatioVsPersistence != null
                    && (!Double.isFinite(readinessMaeRatioVsPersistence)
                            || readinessMaeRatioVsPersistence < 0)) {
                throw new IllegalArgumentException("invalid readiness MAE ratio");
            }
        }
    }
}
