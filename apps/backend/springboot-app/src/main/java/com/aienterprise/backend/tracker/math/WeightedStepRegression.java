package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class WeightedStepRegression {

    private static final double SINGULAR_TOLERANCE = 1e-12;

    public Fit fit(
            List<Observation> input,
            double windowYears,
            List<LocalDate> rollbackDates) {
        if (input == null || input.size() < 2 || !(windowYears > 0)) {
            throw new IllegalArgumentException(
                    "at least two observations and a positive window are required");
        }
        List<Observation> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(Observation::date));
        LocalDate latestDate = sorted.getLast().date();
        double latestYear = toRealYear(latestDate);
        double earliestYear = latestYear - windowYears;
        sorted = sorted.stream()
                .filter(observation -> toRealYear(observation.date()) >= earliestYear)
                .toList();
        if (sorted.size() < 2) {
            throw new IllegalArgumentException("window contains fewer than two observations");
        }
        for (Observation observation : sorted) {
            if (!Double.isFinite(observation.logit())) {
                throw new IllegalArgumentException("observation logit must be finite");
            }
        }

        List<LocalDate> dummies = usableDummies(sorted, rollbackDates);
        while (true) {
            try {
                return fitWithDummies(sorted, latestYear, windowYears, dummies);
            } catch (SingularFit singular) {
                if (dummies.isEmpty()) {
                    throw new IllegalArgumentException("trend observations are rank deficient");
                }
                dummies = new ArrayList<>(dummies.subList(0, dummies.size() - 1));
            }
        }
    }

    private static List<LocalDate> usableDummies(
            List<Observation> observations, List<LocalDate> rollbackDates) {
        Set<LocalDate> unique = new LinkedHashSet<>(rollbackDates == null
                ? List.of() : rollbackDates);
        List<LocalDate> sorted = unique.stream().sorted().toList();
        Set<String> patterns = new LinkedHashSet<>();
        List<LocalDate> usable = new ArrayList<>();
        for (LocalDate date : sorted) {
            boolean before = observations.stream().anyMatch(row -> row.date().isBefore(date));
            boolean after = observations.stream().anyMatch(row -> !row.date().isBefore(date));
            if (!before || !after) {
                continue;
            }
            StringBuilder pattern = new StringBuilder(observations.size());
            observations.forEach(row -> pattern.append(row.date().isBefore(date) ? '0' : '1'));
            if (patterns.add(pattern.toString()) && usable.size() + 2 < observations.size()) {
                usable.add(date);
            }
        }
        return usable;
    }

    private static Fit fitWithDummies(
            List<Observation> observations,
            double latestYear,
            double windowYears,
            List<LocalDate> dummies) {
        int columns = 2 + dummies.size();
        double[][] normal = new double[columns][columns];
        double[] right = new double[columns];
        double halfLife = windowYears / 2.0;

        for (Observation observation : observations) {
            double year = toRealYear(observation.date());
            double weight = Math.exp(-Math.log(2.0) * (latestYear - year) / halfLife);
            double[] x = row(observation, year - latestYear, dummies);
            for (int left = 0; left < columns; left++) {
                right[left] += weight * x[left] * observation.logit();
                for (int column = 0; column < columns; column++) {
                    normal[left][column] += weight * x[left] * x[column];
                }
            }
        }
        double[] coefficients = solve(normal, right);

        double weightedSse = 0;
        for (Observation observation : observations) {
            double year = toRealYear(observation.date());
            double weight = Math.exp(-Math.log(2.0) * (latestYear - year) / halfLife);
            double[] x = row(observation, year - latestYear, dummies);
            double fitted = 0;
            for (int column = 0; column < columns; column++) {
                fitted += coefficients[column] * x[column];
            }
            double residual = observation.logit() - fitted;
            weightedSse += weight * residual * residual;
        }
        double residualSe = Math.sqrt(weightedSse
                / Math.max(1, observations.size() - columns));
        double slope = coefficients[1];
        double intercept = coefficients[0] - slope * latestYear;

        Map<LocalDate, Double> shifts = new LinkedHashMap<>();
        for (int index = 0; index < dummies.size(); index++) {
            shifts.put(dummies.get(index), coefficients[index + 2]);
        }
        return new Fit(
                new Trend(slope, intercept, residualSe),
                shifts,
                observations.size());
    }

    private static double[] row(
            Observation observation, double centeredYear, List<LocalDate> dummies) {
        double[] row = new double[2 + dummies.size()];
        row[0] = 1;
        row[1] = centeredYear;
        for (int index = 0; index < dummies.size(); index++) {
            row[index + 2] = observation.date().isBefore(dummies.get(index)) ? 0 : 1;
        }
        return row;
    }

    private static double[] solve(double[][] source, double[] sourceRight) {
        int size = sourceRight.length;
        double[][] matrix = new double[size][size];
        double[] right = sourceRight.clone();
        for (int row = 0; row < size; row++) {
            matrix[row] = source[row].clone();
        }
        for (int pivot = 0; pivot < size; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[best][pivot])) {
                    best = row;
                }
            }
            if (Math.abs(matrix[best][pivot]) <= SINGULAR_TOLERANCE) {
                throw new SingularFit();
            }
            if (best != pivot) {
                double[] swap = matrix[pivot];
                matrix[pivot] = matrix[best];
                matrix[best] = swap;
                double rightSwap = right[pivot];
                right[pivot] = right[best];
                right[best] = rightSwap;
            }
            double divisor = matrix[pivot][pivot];
            for (int column = pivot; column < size; column++) {
                matrix[pivot][column] /= divisor;
            }
            right[pivot] /= divisor;
            for (int row = 0; row < size; row++) {
                if (row == pivot) {
                    continue;
                }
                double factor = matrix[row][pivot];
                for (int column = pivot; column < size; column++) {
                    matrix[row][column] -= factor * matrix[pivot][column];
                }
                right[row] -= factor * right[pivot];
            }
        }
        for (double value : right) {
            if (!Double.isFinite(value)) {
                throw new SingularFit();
            }
        }
        return right;
    }

    static double toRealYear(LocalDate date) {
        return Year.of(date.getYear()).getValue()
                + (date.getDayOfYear() - 1) / 365.25;
    }

    public record Observation(LocalDate date, double logit) {

        public Observation {
            date = Objects.requireNonNull(date, "date");
        }
    }

    public record Fit(
            Trend trend,
            Map<LocalDate, Double> levelShifts,
            int observations) {

        public Fit {
            trend = Objects.requireNonNull(trend, "trend");
            levelShifts = Collections.unmodifiableMap(new LinkedHashMap<>(levelShifts));
        }
    }

    private static final class SingularFit extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
