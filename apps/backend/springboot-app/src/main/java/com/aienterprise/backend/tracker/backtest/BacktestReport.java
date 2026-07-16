package com.aienterprise.backend.tracker.backtest;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record BacktestReport(
        String reportVersion,
        String inputSha256,
        long seed,
        String datasetSha256,
        String nodeSetVersion,
        String rubricVersion,
        String paramsVersion,
        String graphVersion,
        String candidateRegistryVersion,
        LocalDate asOf,
        LocalDate calibrationStart,
        LocalDate calibrationEnd,
        LocalDate holdoutStart,
        LocalDate holdoutEnd,
        int horizonWeeks,
        int sampleCount,
        int calibrationCutoffCount,
        int holdoutCutoffCount,
        BacktestCandidate selectedCandidate,
        double objectiveScore,
        List<CandidateScore> calibrationCandidates,
        List<FoldResult> folds,
        List<MetricComparison> metrics) {

    public static final String REPORT_VERSION = "backtest-report-v1";

    public BacktestReport {
        reportVersion = required(reportVersion, "reportVersion");
        inputSha256 = sha256(inputSha256, "inputSha256");
        datasetSha256 = sha256(datasetSha256, "datasetSha256");
        nodeSetVersion = required(nodeSetVersion, "nodeSetVersion");
        rubricVersion = required(rubricVersion, "rubricVersion");
        paramsVersion = required(paramsVersion, "paramsVersion");
        graphVersion = required(graphVersion, "graphVersion");
        candidateRegistryVersion = required(
                candidateRegistryVersion, "candidateRegistryVersion");
        asOf = Objects.requireNonNull(asOf, "asOf");
        calibrationStart = Objects.requireNonNull(
                calibrationStart, "calibrationStart");
        calibrationEnd = Objects.requireNonNull(calibrationEnd, "calibrationEnd");
        holdoutStart = Objects.requireNonNull(holdoutStart, "holdoutStart");
        holdoutEnd = Objects.requireNonNull(holdoutEnd, "holdoutEnd");
        selectedCandidate = Objects.requireNonNull(
                selectedCandidate, "selectedCandidate");
        if (seed < 0 || horizonWeeks < 1 || sampleCount < 100
                || calibrationCutoffCount < 1 || holdoutCutoffCount < 1
                || !Double.isFinite(objectiveScore)
                || objectiveScore < 0 || objectiveScore > 1
                || calibrationStart.isAfter(calibrationEnd)
                || !calibrationEnd.isBefore(holdoutStart)
                || holdoutStart.isAfter(holdoutEnd)
                || asOf.isBefore(holdoutEnd)) {
            throw new IllegalArgumentException("invalid backtest report metadata");
        }
        calibrationCandidates = Objects.requireNonNull(
                calibrationCandidates, "calibrationCandidates").stream()
                .sorted(Comparator.comparing(CandidateScore::candidate))
                .toList();
        folds = Objects.requireNonNull(folds, "folds").stream()
                .sorted(Comparator.comparingInt(FoldResult::foldIndex)
                        .thenComparingInt(FoldResult::pillar))
                .toList();
        metrics = Objects.requireNonNull(metrics, "metrics").stream()
                .sorted(Comparator.comparing(MetricComparison::code)
                        .thenComparingInt(MetricComparison::pillar))
                .toList();
    }

    public record CandidateScore(
            BacktestCandidate candidate,
            double objectiveScore,
            Map<String, Double> componentLosses) {

        public CandidateScore {
            candidate = Objects.requireNonNull(candidate, "candidate");
            if (!Double.isFinite(objectiveScore)
                    || objectiveScore < 0 || objectiveScore > 1) {
                throw new IllegalArgumentException("invalid candidate objective");
            }
            Map<String, Double> copied = new LinkedHashMap<>();
            Objects.requireNonNull(componentLosses, "componentLosses")
                    .forEach((name, value) -> {
                        if (name == null || name.isBlank() || value == null
                                || !Double.isFinite(value) || value < 0 || value > 1) {
                            throw new IllegalArgumentException(
                                    "invalid candidate objective component");
                        }
                        copied.put(name, value);
                    });
            componentLosses = Collections.unmodifiableMap(copied);
        }
    }

    public record FoldResult(
            int foldIndex,
            BacktestSchedule.Cohort cohort,
            LocalDate cutoff,
            LocalDate target,
            int pillar,
            Double currentReadiness,
            Double predictedReadiness,
            Double actualReadiness,
            Double predictedLogit,
            Double actualLogit,
            Boolean predictedAdvance,
            Boolean actualAdvance,
            Double intervalP10,
            Double intervalP90,
            Boolean covered,
            Double etaYear,
            BacktestMetric.Status status) {

        public FoldResult {
            cohort = Objects.requireNonNull(cohort, "cohort");
            cutoff = Objects.requireNonNull(cutoff, "cutoff");
            target = Objects.requireNonNull(target, "target");
            status = Objects.requireNonNull(status, "status");
            if (foldIndex < 0 || pillar < 1 || pillar > 6
                    || !target.isAfter(cutoff)) {
                throw new IllegalArgumentException("invalid backtest fold identity");
            }
            if (status == BacktestMetric.Status.OK) {
                if (!unit(currentReadiness) || !unit(predictedReadiness)
                        || !unit(actualReadiness)
                        || !finite(predictedLogit) || !finite(actualLogit)
                        || predictedAdvance == null || actualAdvance == null
                        || !unit(intervalP10) || !unit(intervalP90)
                        || intervalP10 > intervalP90 || covered == null
                        || etaYear != null && !Double.isFinite(etaYear)) {
                    throw new IllegalArgumentException("invalid complete backtest fold");
                }
            }
        }
    }

    public record MetricComparison(
            BacktestMetric.Code code,
            int pillar,
            Double calibrationValue,
            Double holdoutValue,
            int calibrationSamples,
            int holdoutSamples,
            BacktestMetric.Status calibrationStatus,
            BacktestMetric.Status holdoutStatus) {

        public MetricComparison {
            code = Objects.requireNonNull(code, "code");
            calibrationStatus = Objects.requireNonNull(
                    calibrationStatus, "calibrationStatus");
            holdoutStatus = Objects.requireNonNull(holdoutStatus, "holdoutStatus");
            if (pillar < 0 || pillar > 6 || calibrationSamples < 0
                    || holdoutSamples < 0) {
                throw new IllegalArgumentException("invalid metric comparison identity");
            }
            validateMetricSide(
                    calibrationValue, calibrationSamples, calibrationStatus);
            validateMetricSide(holdoutValue, holdoutSamples, holdoutStatus);
        }
    }

    private static void validateMetricSide(
            Double value, int samples, BacktestMetric.Status status) {
        if (status == BacktestMetric.Status.OK) {
            if (value == null || !Double.isFinite(value) || value < 0 || samples < 1) {
                throw new IllegalArgumentException("invalid populated metric");
            }
        } else if (value != null || samples != 0) {
            throw new IllegalArgumentException("insufficient metric must be empty");
        }
    }

    private static boolean unit(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String sha256(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        }
        return value;
    }
}
