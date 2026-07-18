package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class BacktestScheduleTest {

    @Test
    void baselineHasFiftyTwoCalibrationAndSixteenLockedHoldoutCutoffs() {
        BacktestSchedule.Split split = BacktestSchedule.create(
                LocalDate.of(2026, 7, 13));

        assertEquals(52, split.calibration().folds().size());
        assertEquals(16, split.holdout().folds().size());
        assertEquals(LocalDate.of(1957, 1, 7),
                split.calibration().folds().getFirst().cutoff());
        assertEquals(LocalDate.of(2008, 1, 7),
                split.calibration().folds().getLast().cutoff());
        assertEquals(2010, split.holdout().folds().getFirst().cutoff().getYear());
        assertEquals(2025, split.holdout().folds().getLast().cutoff().getYear());
        assertTrue(split.all().stream().allMatch(fold ->
                fold.cutoff().getDayOfWeek() == DayOfWeek.MONDAY
                        && fold.target().equals(fold.cutoff().plusWeeks(52))));
    }

    @Test
    void calibrationOutcomeNeverCrossesThe2010Boundary() {
        BacktestSchedule.Split split = BacktestSchedule.create(
                LocalDate.of(2026, 7, 13));

        assertTrue(split.calibration().folds().stream().allMatch(fold ->
                !fold.target().isAfter(BacktestSchedule.CALIBRATION_END)));
        assertTrue(split.holdout().folds().stream().allMatch(fold ->
                !fold.cutoff().isBefore(BacktestSchedule.HOLDOUT_START)));
        assertTrue(split.all().stream().noneMatch(fold ->
                fold.cutoff().getYear() == 2009));
    }

    @Test
    void excludesTargetsAfterTheLastCompletedMonday() {
        LocalDate completed = LocalDate.of(2011, 1, 3);
        BacktestSchedule.Split split = BacktestSchedule.create(completed);

        assertTrue(split.all().stream().allMatch(fold ->
                !fold.target().isAfter(completed)));
        assertEquals(1, split.holdout().folds().size());
    }

    @Test
    void requiresACompletedMonday() {
        assertThrows(IllegalArgumentException.class, () -> BacktestSchedule.create(
                LocalDate.of(2026, 7, 16)));
    }
}
