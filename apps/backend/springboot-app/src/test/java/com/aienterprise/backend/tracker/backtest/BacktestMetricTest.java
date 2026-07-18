package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BacktestMetricTest {

    @Test
    void aggregatesFiveFixedMetricsByPillarAndMacroOverall() {
        List<BacktestReport.FoldResult> folds = new ArrayList<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            folds.add(fold(0, pillar, LocalDate.of(2007, 1, 1), 2050.0));
            folds.add(fold(1, pillar, LocalDate.of(2008, 1, 7), 2052.0));
        }

        BacktestMetric.Bundle metrics = BacktestMetric.aggregate(folds);

        assertEquals(.05, metrics.get(
                BacktestMetric.Code.READINESS_MAE, 1).value(), 1e-12);
        assertEquals(.20, metrics.get(
                BacktestMetric.Code.LOGIT_READINESS_MAE, 1).value(), 1e-12);
        assertEquals(1.0, metrics.get(
                BacktestMetric.Code.DIRECTION_ACCURACY, 1).value(), 1e-12);
        assertEquals(1.0, metrics.get(
                BacktestMetric.Code.INTERVAL_80_COVERAGE, 1).value(), 1e-12);
        assertEquals(2.0, metrics.get(
                BacktestMetric.Code.ETA_VOLATILITY_YEARS, 1).value(), 1e-12);
        assertEquals(.05, metrics.get(
                BacktestMetric.Code.READINESS_MAE, 0).value(), 1e-12);
        assertEquals(12, metrics.get(
                BacktestMetric.Code.READINESS_MAE, 0).samples());
        assertEquals(6, metrics.get(
                BacktestMetric.Code.ETA_VOLATILITY_YEARS, 0).samples());
    }

    @Test
    void publishesInsufficientEtaVolatilityInsteadOfHidingIt() {
        List<BacktestReport.FoldResult> folds = new ArrayList<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            folds.add(fold(0, pillar, LocalDate.of(2008, 1, 7), null));
        }

        BacktestMetric.Bundle metrics = BacktestMetric.aggregate(folds);

        BacktestMetric.Value pillar = metrics.get(
                BacktestMetric.Code.ETA_VOLATILITY_YEARS, 3);
        assertEquals(BacktestMetric.Status.INSUFFICIENT_DATA, pillar.status());
        assertEquals(null, pillar.value());
        assertEquals(0, pillar.samples());
        assertEquals(BacktestMetric.Status.INSUFFICIENT_DATA, metrics.get(
                BacktestMetric.Code.ETA_VOLATILITY_YEARS, 0).status());
    }

    private static BacktestReport.FoldResult fold(
            int index, int pillar, LocalDate cutoff, Double eta) {
        return new BacktestReport.FoldResult(
                index, BacktestSchedule.Cohort.CALIBRATION,
                cutoff, cutoff.plusWeeks(52), pillar,
                .20, .30, .25, 1.0, .80,
                true, true, .20, .40, true, eta,
                BacktestMetric.Status.OK);
    }
}
