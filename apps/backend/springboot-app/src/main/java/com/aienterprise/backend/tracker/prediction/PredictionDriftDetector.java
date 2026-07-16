package com.aienterprise.backend.tracker.prediction;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded diagnostics for published probabilities; never tunes ETA structure. */
public final class PredictionDriftDetector {

    static final double CALIBRATION_WARNING = 0.15;
    static final double CALIBRATION_FREEZE = 0.25;
    static final double BRIER_WARNING = 0.10;
    static final double BRIER_FREEZE = 0.20;
    static final double CONCENTRATION_WARNING = 0.75;

    public Result detect(
            List<PredictionRepository.CalibrationObservation> observations,
            HazardParameters parameters) {
        List<PredictionRepository.CalibrationObservation> values = List.copyOf(
                Objects.requireNonNull(observations, "observations"));
        Objects.requireNonNull(parameters, "parameters");
        int quarters = (int) values.stream().map(this::quarterKey)
                .distinct().count();
        boolean eligible = values.size() >= parameters.calibrationMinOutcomes()
                && quarters >= parameters.calibrationMinQuarters();
        if (!eligible) {
            return new Result(false, values.size(), quarters,
                    null, null, null, List.of(), false);
        }

        double forecastMean = values.stream()
                .mapToDouble(PredictionRepository.CalibrationObservation
                        ::issuedProbability).average().orElseThrow();
        double outcomeMean = values.stream().mapToInt(value ->
                value.outcome() == PredictionRepository.Outcome.HIT ? 1 : 0)
                .average().orElseThrow();
        double calibrationInLarge = forecastMean - outcomeMean;

        int latestQuarter = values.stream().mapToInt(this::quarterKey)
                .max().orElseThrow();
        double recentBrier = values.stream()
                .filter(value -> quarterKey(value) == latestQuarter)
                .mapToDouble(PredictionDriftDetector::brier)
                .average().orElseThrow();
        double priorBrier = values.stream()
                .filter(value -> quarterKey(value) != latestQuarter)
                .mapToDouble(PredictionDriftDetector::brier)
                .average().orElseThrow();
        double deterioration = recentBrier - priorBrier;

        Map<Integer, Integer> deciles = new HashMap<>();
        for (PredictionRepository.CalibrationObservation value : values) {
            int decile = Math.min(9,
                    (int) Math.floor(value.issuedProbability() * 10));
            deciles.merge(decile, 1, Integer::sum);
        }
        double concentration = deciles.values().stream()
                .mapToInt(Integer::intValue).max().orElseThrow()
                / (double) values.size();

        List<Alert> alerts = new ArrayList<>();
        double absoluteCalibration = Math.abs(calibrationInLarge);
        if (absoluteCalibration >= CALIBRATION_WARNING) {
            boolean freeze = absoluteCalibration >= CALIBRATION_FREEZE;
            alerts.add(new Alert(
                    AlertCode.CALIBRATION_IN_LARGE, calibrationInLarge,
                    CALIBRATION_WARNING, CALIBRATION_FREEZE,
                    freeze ? Severity.FREEZE : Severity.WARNING, freeze));
        }
        if (deterioration >= BRIER_WARNING) {
            boolean freeze = deterioration >= BRIER_FREEZE;
            alerts.add(new Alert(
                    AlertCode.BRIER_DETERIORATION, deterioration,
                    BRIER_WARNING, BRIER_FREEZE,
                    freeze ? Severity.FREEZE : Severity.WARNING, freeze));
        }
        if (concentration >= CONCENTRATION_WARNING) {
            alerts.add(new Alert(
                    AlertCode.PROBABILITY_CONCENTRATION, concentration,
                    CONCENTRATION_WARNING, null, Severity.WARNING, false));
        }
        boolean freeze = alerts.stream().anyMatch(Alert::freezeIssuance);
        return new Result(true, values.size(), quarters,
                calibrationInLarge, deterioration, concentration,
                alerts, freeze);
    }

    private int quarterKey(
            PredictionRepository.CalibrationObservation observation) {
        var date = observation.resolvedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return date.getYear() * 4 + (date.getMonthValue() - 1) / 3;
    }

    private static double brier(
            PredictionRepository.CalibrationObservation observation) {
        int outcome = observation.outcome() == PredictionRepository.Outcome.HIT
                ? 1 : 0;
        double error = observation.issuedProbability() - outcome;
        return error * error;
    }

    public enum AlertCode {
        CALIBRATION_IN_LARGE,
        BRIER_DETERIORATION,
        PROBABILITY_CONCENTRATION
    }

    public enum Severity {
        WARNING,
        FREEZE
    }

    public record Alert(
            AlertCode code,
            double observedValue,
            double warningThreshold,
            Double freezeThreshold,
            Severity severity,
            boolean freezeIssuance) {

        public Alert {
            if (code == null || !Double.isFinite(observedValue)
                    || !Double.isFinite(warningThreshold)
                    || warningThreshold <= 0
                    || freezeThreshold != null
                            && (!Double.isFinite(freezeThreshold)
                                    || freezeThreshold <= warningThreshold)
                    || severity == null
                    || freezeIssuance != (severity == Severity.FREEZE)) {
                throw new IllegalArgumentException("invalid drift alert");
            }
        }
    }

    public record Result(
            boolean eligible,
            int sampleCount,
            int quarterCount,
            Double calibrationInLarge,
            Double brierDeterioration,
            Double maxDecileShare,
            List<Alert> alerts,
            boolean freezeIssuance) {

        public Result {
            alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
            if (sampleCount < 0 || quarterCount < 0
                    || !eligible && (calibrationInLarge != null
                            || brierDeterioration != null
                            || maxDecileShare != null || !alerts.isEmpty()
                            || freezeIssuance)
                    || eligible && (calibrationInLarge == null
                            || brierDeterioration == null
                            || maxDecileShare == null)
                    || freezeIssuance
                            != alerts.stream().anyMatch(Alert::freezeIssuance)) {
                throw new IllegalArgumentException("invalid drift result");
            }
        }
    }
}
