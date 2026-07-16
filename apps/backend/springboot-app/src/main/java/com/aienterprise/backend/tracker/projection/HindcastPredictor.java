package com.aienterprise.backend.tracker.projection;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.EffectiveReadinessEngine;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.math.CompleteTrendModel;
import com.aienterprise.backend.tracker.math.LogitEta;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.PillarTrendResult;
import com.aienterprise.backend.tracker.math.Trend;

public final class HindcastPredictor {

    private static final double MAX_INVALID_FRACTION = .01;

    private final ProjectionSampler sampler;
    private final EffectiveReadinessEngine readinessEngine;

    public HindcastPredictor() {
        this(new ProjectionSampler(), new EffectiveReadinessEngine());
    }

    HindcastPredictor(
            ProjectionSampler sampler,
            EffectiveReadinessEngine readinessEngine) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.readinessEngine = Objects.requireNonNull(
                readinessEngine, "readinessEngine");
    }

    public Result predict(
            long seed,
            int sampleCount,
            List<NodeRow> nodes,
            CapabilityGraph graph,
            ModelParameters model,
            ReadinessResult centralReadiness,
            CompleteTrendModel.Result trends,
            LocalDate cutoff,
            LocalDate target,
            double targetReadiness) {
        validate(seed, sampleCount, nodes, graph, model, centralReadiness,
                trends, cutoff, target, targetReadiness);
        double horizonYears = ChronoUnit.DAYS.between(cutoff, target) / 365.25;
        Map<Integer, List<Double>> draws = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            draws.put(pillar, new ArrayList<>(sampleCount));
        }

        DeterministicRandom random = new DeterministicRandom(seed);
        int invalid = 0;
        for (int index = 0; index < sampleCount; index++) {
            try {
                ProjectionSampler.SampledInputs sampled = sampler.sample(
                        nodes, graph, model, random);
                ReadinessResult sampledReadiness = readinessEngine.calculate(
                        sampled.nodes(), sampled.graph(), sampled.params(), cutoff);
                appendSample(
                        trends, sampled, sampledReadiness, horizonYears,
                        random, draws);
            } catch (RuntimeException invalidSample) {
                invalid++;
            }
        }
        if (invalid / (double) sampleCount > MAX_INVALID_FRACTION) {
            throw new IllegalStateException(
                    "hindcast invalid sample fraction exceeds 1%: "
                            + invalid + "/" + sampleCount);
        }
        int valid = sampleCount - invalid;
        if (valid == 0) {
            throw new IllegalStateException("hindcast has no valid samples");
        }

        Map<Integer, PillarPrediction> predictions = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            PillarTrendResult trend = trends.pillars().get(pillar);
            double current = centralReadiness.effectivePillarReadiness().get(pillar);
            double currentLogit = LogitEta.logitClipped(
                    current, model.params().epsilon());
            double predicted = logistic(currentLogit + trend.trendUsed() * horizonYears);
            Double eta = LogitEta.etaYear(
                    realYear(cutoff), currentLogit,
                    new Trend(trend.trendUsed(), 0, trend.residualSe()),
                    LogitEta.logitClipped(targetReadiness, model.params().epsilon()),
                    model.params());
            List<Double> values = draws.get(pillar);
            values.sort(Double::compareTo);
            predictions.put(pillar, new PillarPrediction(
                    pillar, current, predicted,
                    quantile(values, .10), quantile(values, .90), eta));
        }
        return new Result(
                sampleCount, valid, invalid, horizonYears, predictions);
    }

    private static void appendSample(
            CompleteTrendModel.Result trends,
            ProjectionSampler.SampledInputs sampled,
            ReadinessResult readiness,
            double horizonYears,
            DeterministicRandom random,
            Map<Integer, List<Double>> draws) {
        Map<Integer, Double> fits = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            PillarTrendResult central = trends.pillars().get(pillar);
            Double fit = central.trendFit();
            if (fit != null) {
                fit += random.gaussian()
                        * central.slopeStandardError()
                        * sampled.trendCovarianceScale();
            }
            fits.put(pillar, fit);
        }
        double prior = fits.values().stream()
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .filter(Double::isFinite)
                .average()
                .orElse(0.0);
        for (int pillar = 1; pillar <= 6; pillar++) {
            PillarTrendResult central = trends.pillars().get(pillar);
            Double fit = fits.get(pillar);
            double trendUsed = fit == null
                    ? prior
                    : CompleteTrendModel.shrink(
                            fit, central.eventsInWindow(), prior,
                            sampled.params().kShrink());
            double current = readiness.effectivePillarReadiness().get(pillar);
            double logit = LogitEta.logitClipped(
                    current, sampled.params().epsilon());
            double projected = logistic(logit + trendUsed * horizonYears);
            if (!Double.isFinite(projected) || projected < 0 || projected > 1) {
                throw new IllegalArgumentException("hindcast readiness is invalid");
            }
            draws.get(pillar).add(projected);
        }
    }

    private static double quantile(List<Double> sorted, double quantile) {
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("quantile samples are empty");
        }
        int rank = Math.max(1, (int) Math.ceil(quantile * sorted.size()));
        return sorted.get(rank - 1);
    }

    private static double logistic(double value) {
        if (value >= 0) {
            return 1.0 / (1.0 + Math.exp(-value));
        }
        double exponential = Math.exp(value);
        return exponential / (1.0 + exponential);
    }

    private static double realYear(LocalDate date) {
        return date.getYear() + (date.getDayOfYear() - 1) / 365.25;
    }

    private static void validate(
            long seed,
            int sampleCount,
            List<NodeRow> nodes,
            CapabilityGraph graph,
            ModelParameters model,
            ReadinessResult readiness,
            CompleteTrendModel.Result trends,
            LocalDate cutoff,
            LocalDate target,
            double targetReadiness) {
        if (seed < 0 || sampleCount < 100 || sampleCount > 10_000
                || nodes == null || nodes.isEmpty() || graph == null || model == null
                || readiness == null || trends == null || cutoff == null || target == null
                || !target.isAfter(cutoff)
                || !Double.isFinite(targetReadiness)
                || targetReadiness <= 0 || targetReadiness >= 1
                || !readiness.effectivePillarReadiness().keySet()
                        .equals(java.util.Set.of(1, 2, 3, 4, 5, 6))
                || !trends.pillars().keySet()
                        .equals(java.util.Set.of(1, 2, 3, 4, 5, 6))) {
            throw new IllegalArgumentException("invalid hindcast prediction input");
        }
    }

    public record PillarPrediction(
            int pillar,
            double currentReadiness,
            double predictedReadiness,
            double p10,
            double p90,
            Double etaYear) {

        public PillarPrediction {
            if (pillar < 1 || pillar > 6
                    || !unit(currentReadiness) || !unit(predictedReadiness)
                    || !unit(p10) || !unit(p90) || p10 > p90
                    || etaYear != null && !Double.isFinite(etaYear)) {
                throw new IllegalArgumentException("invalid pillar hindcast prediction");
            }
        }
    }

    public record Result(
            int requestedSamples,
            int validSamples,
            int invalidSamples,
            double horizonYears,
            Map<Integer, PillarPrediction> pillars) {

        public Result {
            if (requestedSamples < 100 || validSamples < 1 || invalidSamples < 0
                    || validSamples + invalidSamples != requestedSamples
                    || !Double.isFinite(horizonYears) || horizonYears <= 0
                    || pillars == null
                    || !pillars.keySet().equals(java.util.Set.of(1, 2, 3, 4, 5, 6))) {
                throw new IllegalArgumentException("invalid hindcast result");
            }
            pillars = Collections.unmodifiableMap(new LinkedHashMap<>(pillars));
        }
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
