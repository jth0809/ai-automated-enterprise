package com.aienterprise.backend.tracker.projection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RightCensoredQuantiles {

    private RightCensoredQuantiles() {
    }

    public static Summary summarize(List<Double> finiteValues, int censoredCount) {
        if (finiteValues == null || censoredCount < 0) {
            throw new IllegalArgumentException("invalid censored population");
        }
        List<Double> sorted = new ArrayList<>(finiteValues);
        sorted.forEach(value -> {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "finite quantile population contains a non-finite value");
            }
        });
        Collections.sort(sorted);
        int population = sorted.size() + censoredCount;
        if (population == 0) {
            throw new IllegalArgumentException("quantile population is empty");
        }
        return new Summary(
                quantile(sorted, population, 0.10),
                quantile(sorted, population, 0.50),
                quantile(sorted, population, 0.90),
                censoredCount / (double) population);
    }

    private static Double quantile(
            List<Double> sortedFinite, int population, double quantile) {
        int rank = (int) Math.ceil(quantile * population);
        return rank <= sortedFinite.size() ? sortedFinite.get(rank - 1) : null;
    }

    public record Summary(
            Double p10,
            Double p50,
            Double p90,
            double censoredFraction) {
    }
}
