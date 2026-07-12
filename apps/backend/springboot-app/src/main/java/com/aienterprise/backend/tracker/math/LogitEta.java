package com.aienterprise.backend.tracker.math;

public final class LogitEta {

    private static final double MIN_POSITIVE_SLOPE = 1e-9;

    private LogitEta() {
    }

    public static double logitClipped(double readiness, double epsilon) {
        if (!Double.isFinite(readiness)) {
            throw new IllegalArgumentException("Readiness must be finite");
        }
        if (!(epsilon > 0 && epsilon < 0.5)) {
            throw new IllegalArgumentException("epsilon must be between 0 and 0.5");
        }
        double clipped = Math.max(epsilon, Math.min(1.0 - epsilon, readiness));
        return Math.log(clipped / (1.0 - clipped));
    }

    public static Trend fitWeightedTrend(double[] years, double[] logits, double windowYears) {
        if (years.length != logits.length || years.length < 2) {
            throw new IllegalArgumentException("At least two paired observations are required");
        }
        if (!(windowYears > 0)) {
            throw new IllegalArgumentException("windowYears must be positive");
        }

        double latest = years[0];
        for (double year : years) {
            latest = Math.max(latest, year);
        }
        double earliest = latest - windowYears;
        double halfLife = windowYears / 2.0;

        double weightSum = 0;
        double weightedYear = 0;
        double weightedLogit = 0;
        int used = 0;
        for (int i = 0; i < years.length; i++) {
            validateFinite(years[i], logits[i]);
            if (years[i] < earliest) {
                continue;
            }
            double weight = Math.exp(-Math.log(2.0) * (latest - years[i]) / halfLife);
            weightSum += weight;
            weightedYear += weight * years[i];
            weightedLogit += weight * logits[i];
            used++;
        }
        if (used < 2) {
            throw new IllegalArgumentException("Window contains fewer than two observations");
        }

        double meanYear = weightedYear / weightSum;
        double meanLogit = weightedLogit / weightSum;
        double covariance = 0;
        double variance = 0;
        for (int i = 0; i < years.length; i++) {
            if (years[i] < earliest) {
                continue;
            }
            double weight = Math.exp(-Math.log(2.0) * (latest - years[i]) / halfLife);
            double dx = years[i] - meanYear;
            covariance += weight * dx * (logits[i] - meanLogit);
            variance += weight * dx * dx;
        }
        double slope = variance == 0 ? 0 : covariance / variance;
        double intercept = meanLogit - slope * meanYear;

        double weightedSse = 0;
        for (int i = 0; i < years.length; i++) {
            if (years[i] < earliest) {
                continue;
            }
            double weight = Math.exp(-Math.log(2.0) * (latest - years[i]) / halfLife);
            double residual = logits[i] - (intercept + slope * years[i]);
            weightedSse += weight * residual * residual;
        }
        double residualSe = Math.sqrt(weightedSse / Math.max(1, used - 2));
        return new Trend(slope, intercept, residualSe);
    }

    public static Double etaYear(
            double nowYear,
            double logitNow,
            Trend trend,
            double logitTarget,
            Params params) {
        if (logitNow >= logitTarget) {
            return nowYear;
        }
        if (!Double.isFinite(trend.slopePerYear()) || trend.slopePerYear() <= MIN_POSITIVE_SLOPE) {
            return null;
        }
        double projected = nowYear + (logitTarget - logitNow) / trend.slopePerYear();
        if (!Double.isFinite(projected) || projected > nowYear + params.etaClampMaxYears()) {
            return null;
        }
        return Math.max(projected, nowYear + params.etaClampMinYears());
    }

    private static void validateFinite(double year, double logit) {
        if (!Double.isFinite(year) || !Double.isFinite(logit)) {
            throw new IllegalArgumentException("Trend observations must be finite");
        }
    }
}
