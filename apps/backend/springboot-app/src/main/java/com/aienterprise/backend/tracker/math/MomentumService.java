package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

public class MomentumService {

    private static final int MIN_OBSERVATIONS = 4;
    private static final double LEVEL_ALPHA = 0.5;
    private static final double TREND_BETA = 0.5;
    private static final double ABSOLUTE_STEADY_BAND = 0.01;
    private static final double RELATIVE_STEADY_BAND = 0.10;
    private static final double DAYS_PER_YEAR = 365.25;

    public Status classify(List<SnapshotRow> input, LocalDate asOf) {
        if (input == null || asOf == null) {
            throw new IllegalArgumentException("history and asOf are required");
        }
        List<SnapshotRow> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(SnapshotRow::snapshotDate)
                .thenComparingLong(SnapshotRow::id));
        Integer pillar = null;
        TreeMap<LocalDate, Double> observations = new TreeMap<>();
        for (SnapshotRow row : sorted) {
            if (row.snapshotDate().isAfter(asOf)) {
                throw new IllegalArgumentException("momentum history exceeds asOf cutoff");
            }
            if (!Double.isFinite(row.logitClipped())) {
                throw new IllegalArgumentException("momentum logit must be finite");
            }
            if (pillar == null) {
                pillar = row.pillar();
            } else if (pillar != row.pillar()) {
                throw new IllegalArgumentException("momentum history mixes pillars");
            }
            observations.put(row.snapshotDate(), row.logitClipped());
        }
        if (observations.size() < MIN_OBSERVATIONS) {
            return Status.INSUFFICIENT_DATA;
        }

        List<java.util.Map.Entry<LocalDate, Double>> points =
                List.copyOf(observations.entrySet());
        double firstDt = yearsBetween(points.get(0).getKey(), points.get(1).getKey());
        double initialTrend = (points.get(1).getValue() - points.get(0).getValue())
                / firstDt;
        double level = points.get(1).getValue();
        double trend = initialTrend;
        LocalDate previousDate = points.get(1).getKey();
        for (int index = 2; index < points.size(); index++) {
            var point = points.get(index);
            double dt = yearsBetween(previousDate, point.getKey());
            double predictedLevel = level + trend * dt;
            double nextLevel = LEVEL_ALPHA * point.getValue()
                    + (1.0 - LEVEL_ALPHA) * predictedLevel;
            double instantaneousTrend = (nextLevel - level) / dt;
            trend = TREND_BETA * instantaneousTrend
                    + (1.0 - TREND_BETA) * trend;
            level = nextLevel;
            previousDate = point.getKey();
        }

        double band = Math.max(
                ABSOLUTE_STEADY_BAND,
                Math.abs(initialTrend) * RELATIVE_STEADY_BAND);
        double change = trend - initialTrend;
        if (change > band) {
            return Status.ACCELERATING;
        }
        if (change < -band) {
            return Status.DECELERATING;
        }
        return Status.STEADY;
    }

    private static double yearsBetween(LocalDate earlier, LocalDate later) {
        long days = ChronoUnit.DAYS.between(earlier, later);
        if (days <= 0) {
            throw new IllegalArgumentException("momentum dates must increase");
        }
        return days / DAYS_PER_YEAR;
    }

    public enum Status {
        ACCELERATING,
        STEADY,
        DECELERATING,
        INSUFFICIENT_DATA
    }
}
