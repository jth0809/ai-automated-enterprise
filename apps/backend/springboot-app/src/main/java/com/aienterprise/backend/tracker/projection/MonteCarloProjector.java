package com.aienterprise.backend.tracker.projection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.aienterprise.backend.tracker.graph.EffectiveReadinessEngine;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.math.CompleteTrendModel;
import com.aienterprise.backend.tracker.math.LogitEta;
import com.aienterprise.backend.tracker.math.PillarTrendResult;
import com.aienterprise.backend.tracker.math.Trend;

public class MonteCarloProjector {

    private static final double MAX_INVALID_FRACTION = 0.01;

    private final ProjectionSampler sampler;
    private final EffectiveReadinessEngine readinessEngine;

    public MonteCarloProjector() {
        this(new ProjectionSampler(), new EffectiveReadinessEngine());
    }

    MonteCarloProjector(
            ProjectionSampler sampler,
            EffectiveReadinessEngine readinessEngine) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.readinessEngine = Objects.requireNonNull(
                readinessEngine, "readinessEngine");
    }

    public ProjectionRunResult project(ProjectionInput input) {
        Objects.requireNonNull(input, "input");
        ProjectionFingerprint.Value fingerprint = ProjectionFingerprint.of(input);
        DeterministicRandom random = new DeterministicRandom(fingerprint.seed());
        Map<Integer, List<Double>> finiteEtas = new LinkedHashMap<>();
        Map<Integer, Integer> censored = new LinkedHashMap<>();
        for (int pillar = 0; pillar <= 6; pillar++) {
            finiteEtas.put(pillar, new ArrayList<>());
            censored.put(pillar, 0);
        }

        int invalid = 0;
        for (int index = 0; index < input.sampleCount(); index++) {
            try {
                ProjectionSampler.SampledInputs sampled = sampler.sample(
                        input.nodes(), input.graph(), input.model(), random);
                ReadinessResult readiness = readinessEngine.calculate(
                        sampled.nodes(), sampled.graph(), sampled.params(), input.asOf());
                Double[] etas = projectSample(input, sampled, readiness, random);
                appendValidSample(etas, finiteEtas, censored);
            } catch (RuntimeException invalidSample) {
                invalid++;
            }
        }

        double invalidFraction = invalid / (double) input.sampleCount();
        if (invalidFraction > MAX_INVALID_FRACTION) {
            throw new IllegalStateException(
                    "projection invalid sample fraction exceeds 1%: "
                            + invalid + "/" + input.sampleCount());
        }
        int valid = input.sampleCount() - invalid;
        Map<Integer, ProjectionResult> results = new LinkedHashMap<>();
        for (int pillar = 0; pillar <= 6; pillar++) {
            RightCensoredQuantiles.Summary summary =
                    RightCensoredQuantiles.summarize(
                            finiteEtas.get(pillar), censored.get(pillar));
            double readiness = pillar == 0
                    ? input.centralReadiness().effectivePillarReadiness().values()
                            .stream().mapToDouble(Double::doubleValue).min().orElseThrow()
                    : input.centralReadiness().effectivePillarReadiness().get(pillar);
            results.put(pillar, new ProjectionResult(
                    pillar, readiness,
                    summary.p10(), summary.p50(), summary.p90(),
                    summary.censoredFraction(), input.momentum().get(pillar)));
        }

        Map<String, Double> diagnostics = new LinkedHashMap<>();
        diagnostics.put("invalid_fraction", invalidFraction);
        diagnostics.put("overall_censored_fraction",
                results.get(0).censoredFraction());
        diagnostics.put("target_readiness", input.targetReadiness());
        return new ProjectionRunResult(
                fingerprint.sha256(), fingerprint.seed(), input.sampleCount(),
                valid, invalid, diagnostics, results);
    }

    private static Double[] projectSample(
            ProjectionInput input,
            ProjectionSampler.SampledInputs sampled,
            ReadinessResult readiness,
            DeterministicRandom random) {
        Map<Integer, Double> sampledFits = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            PillarTrendResult central = input.trends().pillars().get(pillar);
            Double fit = central.trendFit();
            if (fit != null) {
                fit += random.gaussian()
                        * central.slopeStandardError()
                        * sampled.trendCovarianceScale();
            }
            sampledFits.put(pillar, fit);
        }
        double prior = sampledFits.values().stream()
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .filter(Double::isFinite)
                .average()
                .orElse(0.0);

        double nowYear = realYear(input.asOf());
        double targetLogit = LogitEta.logitClipped(
                input.targetReadiness(), sampled.params().epsilon());
        Double[] etas = new Double[7];
        boolean allFinite = true;
        double overall = Double.NEGATIVE_INFINITY;
        for (int pillar = 1; pillar <= 6; pillar++) {
            PillarTrendResult central = input.trends().pillars().get(pillar);
            Double fit = sampledFits.get(pillar);
            double trendUsed = fit == null
                    ? prior
                    : CompleteTrendModel.shrink(
                            fit, central.eventsInWindow(), prior,
                            sampled.params().kShrink());
            double currentReadiness = readiness.effectivePillarReadiness().get(pillar);
            double currentLogit = LogitEta.logitClipped(
                    currentReadiness, sampled.params().epsilon());
            Double eta = LogitEta.etaYear(
                    nowYear, currentLogit,
                    new Trend(trendUsed, 0, central.residualSe()),
                    targetLogit, sampled.params());
            etas[pillar] = eta;
            if (eta == null || !Double.isFinite(eta)) {
                allFinite = false;
            } else {
                overall = Math.max(overall, eta);
            }
        }
        etas[0] = allFinite ? overall : null;
        return etas;
    }

    private static void appendValidSample(
            Double[] etas,
            Map<Integer, List<Double>> finiteEtas,
            Map<Integer, Integer> censored) {
        if (etas == null || etas.length != 7) {
            throw new IllegalArgumentException("sample ETA result is incomplete");
        }
        for (int pillar = 0; pillar <= 6; pillar++) {
            Double eta = etas[pillar];
            if (eta == null) {
                censored.compute(pillar, (ignored, count) -> count + 1);
            } else if (!Double.isFinite(eta)) {
                throw new IllegalArgumentException("sample ETA is non-finite");
            } else {
                finiteEtas.get(pillar).add(eta);
            }
        }
    }

    private static double realYear(LocalDate date) {
        return date.getYear() + (date.getDayOfYear() - 1) / 365.25;
    }
}
