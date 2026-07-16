package com.aienterprise.backend.tracker.backtest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class BacktestSchedule {

    public static final LocalDate CALIBRATION_START = LocalDate.of(1957, 1, 7);
    public static final LocalDate CALIBRATION_END = LocalDate.of(2009, 12, 31);
    public static final LocalDate HOLDOUT_START = LocalDate.of(2010, 1, 1);
    public static final int HORIZON_WEEKS = 52;

    private BacktestSchedule() {
    }

    public static Split create(LocalDate latestCompletedMonday) {
        Objects.requireNonNull(latestCompletedMonday, "latestCompletedMonday");
        if (latestCompletedMonday.getDayOfWeek() != DayOfWeek.MONDAY
                || latestCompletedMonday.isBefore(HOLDOUT_START.plusWeeks(HORIZON_WEEKS))) {
            throw new IllegalArgumentException(
                    "latest completed cutoff target must be a Monday after 2010");
        }

        List<Fold> calibration = new ArrayList<>();
        List<Fold> holdout = new ArrayList<>();
        int index = 0;
        for (int year = CALIBRATION_START.getYear();
                year <= latestCompletedMonday.getYear(); year++) {
            LocalDate cutoff = LocalDate.of(year, 1, 1)
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
            LocalDate target = cutoff.plusWeeks(HORIZON_WEEKS);
            if (target.isAfter(latestCompletedMonday)) {
                continue;
            }
            if (!target.isAfter(CALIBRATION_END)) {
                calibration.add(new Fold(index++, Cohort.CALIBRATION, cutoff, target));
            } else if (!cutoff.isBefore(HOLDOUT_START)) {
                holdout.add(new Fold(index++, Cohort.HOLDOUT, cutoff, target));
            }
        }
        if (calibration.isEmpty() || holdout.isEmpty()) {
            throw new IllegalArgumentException(
                    "both calibration and holdout folds are required");
        }
        return new Split(
                new CalibrationFolds(calibration), new HoldoutFolds(holdout));
    }

    public enum Cohort {
        CALIBRATION,
        HOLDOUT
    }

    public record Fold(
            int index,
            Cohort cohort,
            LocalDate cutoff,
            LocalDate target) {

        public Fold {
            cohort = Objects.requireNonNull(cohort, "cohort");
            cutoff = Objects.requireNonNull(cutoff, "cutoff");
            target = Objects.requireNonNull(target, "target");
            if (index < 0 || cutoff.getDayOfWeek() != DayOfWeek.MONDAY
                    || !target.equals(cutoff.plusWeeks(HORIZON_WEEKS))) {
                throw new IllegalArgumentException("invalid backtest fold");
            }
            if (cohort == Cohort.CALIBRATION && target.isAfter(CALIBRATION_END)
                    || cohort == Cohort.HOLDOUT && cutoff.isBefore(HOLDOUT_START)) {
                throw new IllegalArgumentException("fold crosses its regime boundary");
            }
        }
    }

    public record CalibrationFolds(List<Fold> folds) {
        public CalibrationFolds {
            folds = immutableCohort(folds, Cohort.CALIBRATION);
        }
    }

    public record HoldoutFolds(List<Fold> folds) {
        public HoldoutFolds {
            folds = immutableCohort(folds, Cohort.HOLDOUT);
        }
    }

    public record Split(
            CalibrationFolds calibration,
            HoldoutFolds holdout) {

        public Split {
            calibration = Objects.requireNonNull(calibration, "calibration");
            holdout = Objects.requireNonNull(holdout, "holdout");
        }

        public List<Fold> all() {
            List<Fold> values = new ArrayList<>(
                    calibration.folds().size() + holdout.folds().size());
            values.addAll(calibration.folds());
            values.addAll(holdout.folds());
            values.sort(Comparator.comparingInt(Fold::index));
            return List.copyOf(values);
        }
    }

    private static List<Fold> immutableCohort(
            List<Fold> input, Cohort expected) {
        List<Fold> values = List.copyOf(Objects.requireNonNull(input, "folds"));
        if (values.isEmpty() || values.stream().anyMatch(fold -> fold.cohort() != expected)) {
            throw new IllegalArgumentException("fold cohort is incomplete");
        }
        for (int index = 1; index < values.size(); index++) {
            if (!values.get(index - 1).cutoff().isBefore(values.get(index).cutoff())) {
                throw new IllegalArgumentException("folds must be strictly ordered");
            }
        }
        return values;
    }
}
