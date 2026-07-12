package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogitEtaTest {

    private final Params params = Params.defaults();

    @Test
    void recoversSyntheticLogisticTargetWithinOneYear() {
        int firstYear = 1960;
        int lastYear = 2025;
        double[] years = new double[lastYear - firstYear + 1];
        double[] logits = new double[years.length];
        for (int i = 0; i < years.length; i++) {
            years[i] = firstYear + i;
            double readiness = 1.0 / (1.0 + Math.exp(-0.08 * (years[i] - 2035.0)));
            logits[i] = LogitEta.logitClipped(readiness, params.epsilon());
        }

        Trend trend = LogitEta.fitWeightedTrend(years, logits, 10);
        double nowLogit = logits[logits.length - 1];
        double targetLogit = LogitEta.logitClipped(params.trlMap().get(8), params.epsilon());
        Double eta = LogitEta.etaYear(2025, nowLogit, trend, targetLogit, params);
        double expected = 2035 + targetLogit / 0.08;

        assertEquals(0.08, trend.slopePerYear(), 0.0001);
        assertEquals(expected, eta, 1.0);
    }

    @Test
    void zeroOrNegativeSlopeHasNoFiniteEta() {
        assertNull(LogitEta.etaYear(2025, 0, new Trend(0, 0, 0), 1, params));
        assertNull(LogitEta.etaYear(2025, 0, new Trend(-0.1, 0, 0), 1, params));
    }

    @Test
    void etaBeyondClampHorizonIsUnknown() {
        assertNull(LogitEta.etaYear(2025, 0, new Trend(0.001, 0, 0), 1, params));
    }

    @Test
    void clippingMakesBoundaryLogitsFinite() {
        double atZero = LogitEta.logitClipped(0.0, 0.01);
        double atOne = LogitEta.logitClipped(1.0, 0.01);

        assertTrue(Double.isFinite(atZero));
        assertTrue(Double.isFinite(atOne));
        assertEquals(Math.log(0.01 / 0.99), atZero, 0.0000001);
    }
}
