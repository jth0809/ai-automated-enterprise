package com.aienterprise.backend.tracker.ops;

import java.util.List;

public final class ControlChart {

    private static final int MIN_SAMPLE_DAYS = 14;
    private static final int MAX_SAMPLE_DAYS = 28;

    private ControlChart() {
    }

    public static Result evaluate(
            List<Double> baselineValues,
            double currentValue,
            int previousConsecutiveViolations,
            MetricKind kind) {
        if (baselineValues == null || baselineValues.size() > MAX_SAMPLE_DAYS) {
            throw new IllegalArgumentException("control-chart baseline must contain at most 28 days");
        }
        if (kind == null || !Double.isFinite(currentValue) || currentValue < 0
                || (kind == MetricKind.RATIO && currentValue > 1)) {
            throw new IllegalArgumentException("invalid control-chart value");
        }
        for (Double value : baselineValues) {
            if (value == null || !Double.isFinite(value) || value < 0
                    || (kind == MetricKind.RATIO && value > 1)) {
                throw new IllegalArgumentException("invalid control-chart baseline value");
            }
        }
        int sampleDays = baselineValues.size();
        if (sampleDays < MIN_SAMPLE_DAYS) {
            return new Result(
                    Status.INSUFFICIENT_DATA, false, 0, sampleDays,
                    null, null, null);
        }

        double mean = baselineValues.stream().mapToDouble(Double::doubleValue)
                .average().orElseThrow();
        double variance = baselineValues.stream()
                .mapToDouble(value -> {
                    double delta = value - mean;
                    return delta * delta;
                })
                .average().orElseThrow();
        double sigma = Math.sqrt(variance);
        double lower = mean - 3.0 * sigma;
        double upper = mean + 3.0 * sigma;
        if (kind == MetricKind.RATIO) {
            lower = Math.max(0.0, lower);
            upper = Math.min(1.0, upper);
        }

        boolean violation = currentValue < lower || currentValue > upper;
        int consecutive = violation
                ? Math.min(MAX_SAMPLE_DAYS, Math.max(0, previousConsecutiveViolations) + 1)
                : 0;
        Status status = !violation
                ? Status.OK
                : consecutive >= 2 ? Status.TRIGGERED : Status.WARNING;
        return new Result(status, violation, consecutive, sampleDays, mean, lower, upper);
    }

    public enum MetricKind {
        RATIO,
        NON_NEGATIVE
    }

    public enum Status {
        INSUFFICIENT_DATA,
        OK,
        WARNING,
        TRIGGERED
    }

    public record Result(
            Status status,
            boolean violation,
            int consecutiveViolations,
            int sampleDays,
            Double baselineMean,
            Double lowerBound,
            Double upperBound) {
    }
}
