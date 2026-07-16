package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class WeightedStepRegressionTest {

    private final WeightedStepRegression regression = new WeightedStepRegression();

    @Test
    void rollbackDummyRecoversCalendarSlopeWithoutPermanentDecline() {
        List<WeightedStepRegression.Observation> observations = new ArrayList<>();
        for (int year = 2010; year <= 2020; year++) {
            double logit = 0.10 * (year - 2010);
            if (year >= 2015) {
                logit -= 0.50;
            }
            observations.add(new WeightedStepRegression.Observation(
                    LocalDate.of(year, 1, 1), logit));
        }

        WeightedStepRegression.Fit fit = regression.fit(
                observations, 15, List.of(LocalDate.of(2015, 1, 1)));

        assertEquals(0.10, fit.trend().slopePerYear(), 0.001);
        assertEquals(-0.50, fit.levelShifts().get(LocalDate.of(2015, 1, 1)), 0.01);
        assertTrue(fit.trend().slopePerYear() > 0);
    }

    @Test
    void inputOrderAndDuplicateShiftDatesDoNotChangeFit() {
        List<WeightedStepRegression.Observation> ordered = List.of(
                observation("2018-01-01", -1.0),
                observation("2019-01-01", -0.8),
                observation("2020-01-01", -1.0),
                observation("2021-01-01", -0.8),
                observation("2022-01-01", -0.6));
        List<WeightedStepRegression.Observation> reversed = new ArrayList<>(ordered);
        Collections.reverse(reversed);

        WeightedStepRegression.Fit first = regression.fit(
                ordered, 10,
                List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 1)));
        WeightedStepRegression.Fit second = regression.fit(
                reversed, 10, List.of(LocalDate.of(2020, 1, 1)));

        assertEquals(first.trend().slopePerYear(), second.trend().slopePerYear(), 1e-12);
        assertEquals(first.levelShifts(), second.levelShifts());
    }

    private static WeightedStepRegression.Observation observation(String date, double logit) {
        return new WeightedStepRegression.Observation(LocalDate.parse(date), logit);
    }
}
