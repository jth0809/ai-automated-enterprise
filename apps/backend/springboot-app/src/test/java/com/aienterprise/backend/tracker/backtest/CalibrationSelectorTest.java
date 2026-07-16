package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.math.LogitEta;

class CalibrationSelectorTest {

    @Test
    void computesThePredeclaredEqualWeightObjectiveExactly() {
        double epsilon = .01;
        double logitRange = 2 * Math.abs(LogitEta.logitClipped(0, epsilon));
        BacktestMetric.Bundle bundle = overallBundle(
                .10, .20 * logitRange, .75, .60, 15.0, false);

        BacktestReport.CandidateScore score = new CalibrationSelector(epsilon)
                .score(new CalibrationSelector.CandidateMetrics(
                        BacktestCandidate.DEFAULT, bundle));

        assertEquals(.18, score.objectiveScore(), 1e-12);
        assertEquals(.10, score.componentLosses().get("readiness_mae"), 1e-12);
        assertEquals(.20, score.componentLosses().get("logit_mae"), 1e-12);
        assertEquals(.25, score.componentLosses().get("direction_error"), 1e-12);
        assertEquals(.25, score.componentLosses().get("coverage_deviation"), 1e-12);
        assertEquals(.10, score.componentLosses().get("eta_volatility"), 1e-12);
    }

    @Test
    void exactTieSelectsTheApprovedDefaultWithoutHoldoutInput() {
        BacktestMetric.Bundle tied = overallBundle(.1, .2, .7, .8, 10.0, false);
        List<CalibrationSelector.CandidateMetrics> candidates = new ArrayList<>();
        BacktestCandidate.registry().forEach(candidate -> candidates.add(
                new CalibrationSelector.CandidateMetrics(candidate, tied)));

        CalibrationSelector.Selection selected = new CalibrationSelector(.01)
                .select(new CalibrationSelector.CalibrationPool(candidates));

        assertEquals(BacktestCandidate.DEFAULT, selected.candidate());
        assertEquals(27, selected.scores().size());
    }

    @Test
    void missingEtaVolatilityUsesMaximumLossButCandidateRemainsReportable() {
        BacktestMetric.Bundle sparse = overallBundle(.1, .2, .7, .8, null, true);

        BacktestReport.CandidateScore score = new CalibrationSelector(.01)
                .score(new CalibrationSelector.CandidateMetrics(
                        BacktestCandidate.DEFAULT, sparse));

        assertEquals(1.0, score.componentLosses().get("eta_volatility"), 1e-12);
    }

    static BacktestMetric.Bundle overallBundle(
            double readiness,
            double logit,
            double direction,
            double coverage,
            Double volatility,
            boolean insufficientVolatility) {
        Map<BacktestMetric.Key, BacktestMetric.Value> values = new LinkedHashMap<>();
        put(values, BacktestMetric.Code.READINESS_MAE, readiness);
        put(values, BacktestMetric.Code.LOGIT_READINESS_MAE, logit);
        put(values, BacktestMetric.Code.DIRECTION_ACCURACY, direction);
        put(values, BacktestMetric.Code.INTERVAL_80_COVERAGE, coverage);
        values.put(new BacktestMetric.Key(
                        BacktestMetric.Code.ETA_VOLATILITY_YEARS, 0),
                new BacktestMetric.Value(
                        BacktestMetric.Code.ETA_VOLATILITY_YEARS, 0,
                        volatility, insufficientVolatility ? 0 : 1,
                        insufficientVolatility
                                ? BacktestMetric.Status.INSUFFICIENT_DATA
                                : BacktestMetric.Status.OK));
        return new BacktestMetric.Bundle(values);
    }

    private static void put(
            Map<BacktestMetric.Key, BacktestMetric.Value> values,
            BacktestMetric.Code code,
            double value) {
        values.put(new BacktestMetric.Key(code, 0),
                new BacktestMetric.Value(
                        code, 0, value, 1, BacktestMetric.Status.OK));
    }
}
