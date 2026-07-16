package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PavaCalibratorTest {

    @Test
    void poolsViolationsAndPublishesAMonotoneStableModel() {
        PavaCalibrator.Model model = new PavaCalibrator().fit(List.of(
                new PavaCalibrator.Sample(0.10, 1),
                new PavaCalibrator.Sample(0.20, 0),
                new PavaCalibrator.Sample(0.80, 1)));

        assertEquals(List.of(
                new PavaCalibrator.Knot(0.15, 0.5),
                new PavaCalibrator.Knot(0.8, 1.0)), model.knots());
        double previous = -1;
        for (int step = 0; step <= 100; step++) {
            double calibrated = model.apply(step / 100.0);
            assertTrue(calibrated >= previous);
            assertTrue(calibrated >= 0 && calibrated <= 1);
            previous = calibrated;
        }
    }

    @Test
    void knotEncodingIsByteStableAndRoundTripsExactly() {
        PavaCalibrator.Model model = new PavaCalibrator().fit(List.of(
                new PavaCalibrator.Sample(0.1, 0),
                new PavaCalibrator.Sample(0.4, 1),
                new PavaCalibrator.Sample(0.9, 1)));

        String first = model.toJson();
        String second = new PavaCalibrator().fit(List.of(
                new PavaCalibrator.Sample(0.9, 1),
                new PavaCalibrator.Sample(0.1, 0),
                new PavaCalibrator.Sample(0.4, 1))).toJson();

        assertEquals(first, second);
        assertEquals(model, PavaCalibrator.Model.fromJson(first));
    }
}
