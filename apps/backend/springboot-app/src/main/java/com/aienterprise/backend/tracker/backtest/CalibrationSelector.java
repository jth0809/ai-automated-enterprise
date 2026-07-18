package com.aienterprise.backend.tracker.backtest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aienterprise.backend.tracker.math.LogitEta;

public final class CalibrationSelector {

    private static final double TIE_TOLERANCE = 1e-12;

    private final double logitRange;

    public CalibrationSelector(double epsilon) {
        if (!Double.isFinite(epsilon) || epsilon <= 0 || epsilon >= .5) {
            throw new IllegalArgumentException("epsilon must be within (0,.5)");
        }
        this.logitRange = 2 * Math.abs(LogitEta.logitClipped(0, epsilon));
    }

    public Selection select(CalibrationPool pool) {
        Objects.requireNonNull(pool, "pool");
        List<BacktestReport.CandidateScore> scores = pool.candidates().stream()
                .map(this::score)
                .sorted(Comparator.comparing(BacktestReport.CandidateScore::candidate))
                .toList();
        BacktestReport.CandidateScore best = null;
        for (BacktestReport.CandidateScore score : scores) {
            if (best == null || better(score, best)) {
                best = score;
            }
        }
        return new Selection(
                Objects.requireNonNull(best).candidate(),
                best.objectiveScore(), scores);
    }

    public BacktestReport.CandidateScore score(CandidateMetrics input) {
        Objects.requireNonNull(input, "input");
        BacktestMetric.Bundle metrics = input.metrics();
        double readiness = metric(metrics, BacktestMetric.Code.READINESS_MAE);
        double logit = clamp(
                metric(metrics, BacktestMetric.Code.LOGIT_READINESS_MAE)
                        / logitRange);
        double direction = clamp(1 - metric(
                metrics, BacktestMetric.Code.DIRECTION_ACCURACY));
        double coverage = clamp(Math.abs(metric(
                metrics, BacktestMetric.Code.INTERVAL_80_COVERAGE) - .80) / .80);
        BacktestMetric.Value volatilityValue = metrics.get(
                BacktestMetric.Code.ETA_VOLATILITY_YEARS, 0);
        double volatility = volatilityValue.status() == BacktestMetric.Status.OK
                ? clamp(volatilityValue.value() / 150.0)
                : 1.0;

        Map<String, Double> components = new LinkedHashMap<>();
        components.put("readiness_mae", clamp(readiness));
        components.put("logit_mae", logit);
        components.put("direction_error", direction);
        components.put("coverage_deviation", coverage);
        components.put("eta_volatility", volatility);
        double objective = components.values().stream()
                .mapToDouble(Double::doubleValue).average().orElseThrow();
        return new BacktestReport.CandidateScore(
                input.candidate(), objective, components);
    }

    private static double metric(
            BacktestMetric.Bundle metrics, BacktestMetric.Code code) {
        BacktestMetric.Value value = metrics.get(code, 0);
        if (value.status() != BacktestMetric.Status.OK) {
            throw new IllegalArgumentException(
                    "calibration objective metric is insufficient: " + code);
        }
        return value.value();
    }

    private static boolean better(
            BacktestReport.CandidateScore candidate,
            BacktestReport.CandidateScore current) {
        double difference = candidate.objectiveScore() - current.objectiveScore();
        if (difference < -TIE_TOLERANCE) {
            return true;
        }
        if (Math.abs(difference) > TIE_TOLERANCE) {
            return false;
        }
        int byDistance = Double.compare(
                candidate.candidate().defaultDistance(),
                current.candidate().defaultDistance());
        return byDistance < 0 || byDistance == 0
                && candidate.candidate().compareTo(current.candidate()) < 0;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("objective component must be finite");
        }
        return Math.max(0, Math.min(1, value));
    }

    public record CandidateMetrics(
            BacktestCandidate candidate,
            BacktestMetric.Bundle metrics) {
        public CandidateMetrics {
            candidate = Objects.requireNonNull(candidate, "candidate");
            metrics = Objects.requireNonNull(metrics, "metrics");
        }
    }

    public record CalibrationPool(List<CandidateMetrics> candidates) {
        public CalibrationPool {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            Set<BacktestCandidate> unique = new HashSet<>();
            candidates.forEach(value -> unique.add(value.candidate()));
            if (candidates.size() != BacktestCandidate.registry().size()
                    || !unique.equals(new HashSet<>(BacktestCandidate.registry()))) {
                throw new IllegalArgumentException(
                        "calibration pool must contain the exact frozen registry");
            }
        }
    }

    public record Selection(
            BacktestCandidate candidate,
            double objectiveScore,
            List<BacktestReport.CandidateScore> scores) {
        public Selection {
            candidate = Objects.requireNonNull(candidate, "candidate");
            if (!Double.isFinite(objectiveScore)
                    || objectiveScore < 0 || objectiveScore > 1) {
                throw new IllegalArgumentException("invalid selected objective");
            }
            scores = List.copyOf(Objects.requireNonNull(scores, "scores"));
        }
    }
}
