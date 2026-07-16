package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

public class CompleteTrendModel {

    private static final Set<Integer> PILLARS = Set.of(1, 2, 3, 4, 5, 6);
    private static final double Z_80_PERCENT = 1.2816;

    private final AdaptiveWindow adaptiveWindow;
    private final WeightedStepRegression regression;

    public CompleteTrendModel() {
        this(new AdaptiveWindow(), new WeightedStepRegression());
    }

    CompleteTrendModel(
            AdaptiveWindow adaptiveWindow,
            WeightedStepRegression regression) {
        this.adaptiveWindow = Objects.requireNonNull(adaptiveWindow, "adaptiveWindow");
        this.regression = Objects.requireNonNull(regression, "regression");
    }

    public Result calculate(
            Map<Integer, Double> readiness,
            Map<Integer, List<SnapshotRow>> histories,
            Map<Integer, List<StateChangeEvent>> stateChanges,
            Map<Integer, RegimeBreak> regimeBreaks,
            Params params,
            LocalDate asOf,
            double targetReadiness) {
        validateInputs(readiness, histories, stateChanges, regimeBreaks,
                params, asOf, targetReadiness);

        Map<Integer, Draft> drafts = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            AdaptiveWindow.Selection selection = adaptiveWindow.select(
                    pillar,
                    stateChanges.getOrDefault(pillar, List.of()),
                    regimeBreaks.get(pillar), asOf, params);
            drafts.put(pillar, fitPillar(
                    pillar, readiness.get(pillar),
                    histories.getOrDefault(pillar, List.of()),
                    selection, asOf, params));
        }

        double prior = drafts.values().stream()
                .map(Draft::trendFit)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .filter(Double::isFinite)
                .average()
                .orElse(0.0);

        double nowYear = WeightedStepRegression.toRealYear(asOf);
        double targetLogit = LogitEta.logitClipped(targetReadiness, params.epsilon());
        Map<Integer, PillarTrendResult> results = new LinkedHashMap<>();
        drafts.forEach((pillar, draft) -> {
            double trendUsed = draft.trendFit() == null
                    ? prior
                    : shrink(draft.trendFit(), draft.selection().eventsInWindow(),
                            prior, params.kShrink());
            double logitNow = LogitEta.logitClipped(draft.readiness(), params.epsilon());
            Trend etaTrend = new Trend(trendUsed, 0.0, draft.residualSe());
            Double eta = LogitEta.etaYear(
                    nowYear, logitNow, etaTrend, targetLogit, params);
            Interval interval = interval(
                    eta, nowYear, trendUsed, draft.residualSe(), params);
            RegimeBreak regime = regimeBreaks.get(pillar);
            results.put(pillar, new PillarTrendResult(
                    pillar, draft.readiness(), draft.trendFit(), prior, trendUsed,
                    draft.selection().eventsInWindow(), draft.selection().windowYears(),
                    draft.selection().medianIntervalYears(),
                    regime == null ? null : regime.id(),
                    draft.selection().regimeStart(),
                    eta, interval.low(), interval.high(), draft.residualSe(),
                    draft.levelShifts(), draft.observations()));
        });
        return new Result(prior, results);
    }

    public static double shrink(
            double trendFit, int eventsInWindow,
            double trendPrior, double k) {
        if (!Double.isFinite(trendFit) || !Double.isFinite(trendPrior)
                || eventsInWindow < 0 || !Double.isFinite(k) || k <= 0) {
            throw new IllegalArgumentException("invalid shrinkage input");
        }
        return (eventsInWindow * trendFit + k * trendPrior)
                / (eventsInWindow + k);
    }

    private Draft fitPillar(
            int pillar,
            double currentReadiness,
            List<SnapshotRow> input,
            AdaptiveWindow.Selection selection,
            LocalDate asOf,
            Params params) {
        List<SnapshotRow> rows = new ArrayList<>(input);
        rows.sort(java.util.Comparator.comparing(SnapshotRow::snapshotDate)
                .thenComparingLong(SnapshotRow::id));
        TreeMap<LocalDate, WeightedStepRegression.Observation> observations =
                new TreeMap<>();
        for (SnapshotRow row : rows) {
            if (row.pillar() != pillar) {
                throw new IllegalArgumentException("snapshot belongs to another pillar");
            }
            if (row.snapshotDate().isAfter(asOf)) {
                throw new IllegalArgumentException("snapshot exceeds asOf cutoff");
            }
            if (!Double.isFinite(row.readiness())
                    || row.readiness() < 0 || row.readiness() > 1) {
                throw new IllegalArgumentException("snapshot readiness is invalid");
            }
            if (row.snapshotDate().isBefore(selection.windowStart())) {
                continue;
            }
            observations.put(row.snapshotDate(),
                    new WeightedStepRegression.Observation(
                            row.snapshotDate(),
                            LogitEta.logitClipped(row.readiness(), params.epsilon())));
        }
        observations.put(asOf, new WeightedStepRegression.Observation(
                asOf, LogitEta.logitClipped(currentReadiness, params.epsilon())));

        if (observations.size() < 2) {
            return new Draft(currentReadiness, selection, null,
                    0.0, Map.of(), observations.size());
        }
        try {
            WeightedStepRegression.Fit fit = regression.fit(
                    List.copyOf(observations.values()),
                    selection.windowYears(), selection.rollbackDates());
            return new Draft(
                    currentReadiness, selection, fit.trend().slopePerYear(),
                    fit.trend().residualSe(), fit.levelShifts(), fit.observations());
        } catch (IllegalArgumentException insufficientOrRankDeficient) {
            return new Draft(currentReadiness, selection, null,
                    0.0, Map.of(), observations.size());
        }
    }

    private static Interval interval(
            Double eta, double nowYear, double trendUsed,
            double residualSe, Params params) {
        if (eta == null) {
            return new Interval(null, null);
        }
        if (!(trendUsed > 0) || !Double.isFinite(residualSe)) {
            return new Interval(eta, eta);
        }
        double halfWidth = Z_80_PERCENT * residualSe / trendUsed;
        double low = Math.max(
                nowYear + params.etaClampMinYears(), eta - halfWidth);
        double high = Math.min(
                nowYear + params.etaClampMaxYears(), eta + halfWidth);
        return new Interval(Math.min(low, eta), Math.max(high, eta));
    }

    private static void validateInputs(
            Map<Integer, Double> readiness,
            Map<Integer, List<SnapshotRow>> histories,
            Map<Integer, List<StateChangeEvent>> stateChanges,
            Map<Integer, RegimeBreak> regimeBreaks,
            Params params,
            LocalDate asOf,
            double targetReadiness) {
        if (readiness == null || !readiness.keySet().equals(PILLARS)
                || histories == null || stateChanges == null || regimeBreaks == null
                || params == null || asOf == null
                || !Double.isFinite(targetReadiness)
                || targetReadiness <= 0 || targetReadiness >= 1
                || !Double.isFinite(params.kShrink()) || params.kShrink() <= 0) {
            throw new IllegalArgumentException("complete trend inputs are invalid");
        }
        if (!PILLARS.containsAll(histories.keySet())
                || !PILLARS.containsAll(stateChanges.keySet())
                || !PILLARS.containsAll(regimeBreaks.keySet())) {
            throw new IllegalArgumentException("unknown pillar input");
        }
        readiness.values().forEach(value -> {
            if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("readiness must be within [0, 1]");
            }
        });
    }

    private record Draft(
            double readiness,
            AdaptiveWindow.Selection selection,
            Double trendFit,
            double residualSe,
            Map<LocalDate, Double> levelShifts,
            int observations) {
    }

    private record Interval(Double low, Double high) {
    }

    public record Result(
            double priorSlope,
            Map<Integer, PillarTrendResult> pillars) {

        public Result {
            if (!Double.isFinite(priorSlope) || pillars == null
                    || !pillars.keySet().equals(PILLARS)) {
                throw new IllegalArgumentException("invalid complete trend result");
            }
            pillars = Collections.unmodifiableMap(new LinkedHashMap<>(pillars));
        }
    }
}
