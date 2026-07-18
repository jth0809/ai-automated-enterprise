package com.aienterprise.backend.tracker.prediction;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Gated identity/PAVA calibration with chronological out-of-sample audit. */
@Service
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class PredictionCalibrationService
        implements PredictionIssuanceService.CalibrationSource {

    private static final String INPUT_VERSION = "prediction-calibration-input-v1";
    private static final String PAVA_VERSION = "pava-v1";

    private final PredictionRepository repository;
    private final PavaCalibrator calibrator;
    private final PredictionDriftDetector driftDetector;

    @Autowired
    public PredictionCalibrationService(PredictionRepository repository) {
        this(repository, new PavaCalibrator(), new PredictionDriftDetector());
    }

    PredictionCalibrationService(
            PredictionRepository repository,
            PavaCalibrator calibrator,
            PredictionDriftDetector driftDetector) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.calibrator = Objects.requireNonNull(calibrator, "calibrator");
        this.driftDetector = Objects.requireNonNull(
                driftDetector, "driftDetector");
    }

    public Result calibrate() {
        HazardParameters parameters = repository.loadActiveParameters();
        List<PredictionRepository.CalibrationObservation> observations =
                new ArrayList<>(repository.findCalibrationObservations());
        observations.sort(Comparator
                .comparing(PredictionRepository.CalibrationObservation::resolvedAt)
                .thenComparingLong(
                        PredictionRepository.CalibrationObservation::id));
        int quarterCount = (int) observations.stream()
                .mapToInt(PredictionCalibrationService::quarterKey)
                .distinct().count();
        String inputSha256 = inputFingerprint(parameters, observations);
        boolean eligible = observations.size()
                >= parameters.calibrationMinOutcomes()
                && quarterCount >= parameters.calibrationMinQuarters();

        PredictionRepository.CalibrationDraft draft = eligible
                ? pavaDraft(inputSha256, observations, quarterCount)
                : identityDraft(inputSha256, observations.size(), quarterCount,
                        parameters);
        PredictionRepository.CalibrationSaveResult saved =
                repository.saveCalibration(draft);

        PredictionDriftDetector.Result drift = driftDetector.detect(
                observations, parameters);
        List<PredictionRepository.DriftAlertDraft> alertDrafts = drift.alerts()
                .stream().map(alert -> driftDraft(
                        saved.calibration().calibrationVersion(), alert)).toList();
        List<PredictionRepository.StoredDriftAlert> alerts =
                repository.saveDriftAlerts(
                        saved.calibration().calibrationVersion(), alertDrafts);
        boolean frozen = repository.isIssuanceFrozen(
                saved.calibration().calibrationVersion());
        return new Result(saved.calibration(), alerts, saved.reused(), frozen);
    }

    @Override
    public PredictionCandidateSelector.Calibration current() {
        PredictionRepository.StoredCalibration stored = repository
                .findCurrentCalibration().orElseThrow(() ->
                        new IllegalStateException(
                                "prediction calibration has not been initialized"));
        if (repository.isIssuanceFrozen(stored.calibrationVersion())) {
            throw new IllegalStateException(
                    "prediction issuance is frozen by a drift alert");
        }
        if (stored.method() == PredictionRepository.CalibrationMethod.IDENTITY) {
            return new PredictionCandidateSelector.Calibration(
                    stored.calibrationVersion(), value -> value);
        }
        PavaCalibrator.Model model = PavaCalibrator.Model.fromJson(
                stored.knotsJson());
        return new PredictionCandidateSelector.Calibration(
                stored.calibrationVersion(), model::apply);
    }

    private PredictionRepository.CalibrationDraft pavaDraft(
            String inputSha256,
            List<PredictionRepository.CalibrationObservation> observations,
            int quarterCount) {
        List<Integer> quarters = observations.stream()
                .mapToInt(PredictionCalibrationService::quarterKey)
                .distinct().sorted().boxed().toList();
        List<Double> rawErrors = new ArrayList<>();
        List<Double> calibratedErrors = new ArrayList<>();
        List<Double> calibratedForecasts = new ArrayList<>();
        List<Integer> outcomes = new ArrayList<>();
        int folds = 0;
        for (int index = 3; index < quarters.size(); index++) {
            int testQuarter = quarters.get(index);
            List<PredictionRepository.CalibrationObservation> training =
                    observations.stream().filter(value ->
                            quarterKey(value) < testQuarter).toList();
            PavaCalibrator.Model foldModel = calibrator.fit(samples(training));
            for (PredictionRepository.CalibrationObservation test
                    : observations.stream().filter(value ->
                            quarterKey(value) == testQuarter).toList()) {
                int outcome = test.outcomeBinary();
                double calibrated = foldModel.apply(test.rawProbability());
                rawErrors.add(square(test.rawProbability() - outcome));
                calibratedErrors.add(square(calibrated - outcome));
                calibratedForecasts.add(calibrated);
                outcomes.add(outcome);
            }
            folds++;
        }
        if (rawErrors.isEmpty()) {
            throw new IllegalStateException(
                    "eligible calibration produced no time-ordered OOS fold");
        }
        PavaCalibrator.Model finalModel = calibrator.fit(samples(observations));
        double rawBrier = mean(rawErrors);
        double calibratedBrier = mean(calibratedErrors);
        double calibrationInLarge = mean(calibratedForecasts)
                - outcomes.stream().mapToInt(Integer::intValue)
                        .average().orElseThrow();
        String version = "calibration-pava-v1-"
                + inputSha256.substring(0, 12);
        return new PredictionRepository.CalibrationDraft(
                version, inputSha256, PredictionRepository.CalibrationMethod.PAVA,
                PredictionRepository.CalibrationStatus.OK,
                observations.size(), quarterCount, finalModel.toJson(),
                rawBrier, calibratedBrier, calibrationInLarge,
                "algorithm=" + PAVA_VERSION + ";folds=" + folds
                        + ";oos_samples=" + rawErrors.size()
                        + ";ordering=resolved_at,id");
    }

    private static PredictionRepository.CalibrationDraft identityDraft(
            String inputSha256,
            int sampleCount,
            int quarterCount,
            HazardParameters parameters) {
        return new PredictionRepository.CalibrationDraft(
                "calibration-identity-v1-" + inputSha256.substring(0, 12),
                inputSha256, PredictionRepository.CalibrationMethod.IDENTITY,
                PredictionRepository.CalibrationStatus
                        .INSUFFICIENT_CALIBRATION_DATA,
                sampleCount, quarterCount, "[]", null, null, null,
                "algorithm=identity-v1;samples=" + sampleCount
                        + ";quarters=" + quarterCount
                        + ";required=" + parameters.calibrationMinOutcomes()
                        + "/" + parameters.calibrationMinQuarters());
    }

    private static PredictionRepository.DriftAlertDraft driftDraft(
            String calibrationVersion,
            PredictionDriftDetector.Alert alert) {
        String canonical = String.join("|",
                "prediction-drift-alert-v1", calibrationVersion,
                alert.code().name(), Double.toHexString(alert.observedValue()),
                Double.toHexString(alert.warningThreshold()),
                alert.freezeThreshold() == null ? "null"
                        : Double.toHexString(alert.freezeThreshold()),
                alert.severity().name(),
                Boolean.toString(alert.freezeIssuance()));
        return new PredictionRepository.DriftAlertDraft(
                alert.code(), alert.observedValue(), alert.warningThreshold(),
                alert.freezeThreshold(), alert.severity(),
                alert.freezeIssuance(), PredictionFingerprint.sha256(canonical));
    }

    private static String inputFingerprint(
            HazardParameters parameters,
            List<PredictionRepository.CalibrationObservation> observations) {
        StringBuilder canonical = new StringBuilder(INPUT_VERSION).append('\n')
                .append("hazard=").append(parameters.version()).append('\n')
                .append("minimum_outcomes=")
                .append(parameters.calibrationMinOutcomes()).append('\n')
                .append("minimum_quarters=")
                .append(parameters.calibrationMinQuarters()).append('\n');
        for (PredictionRepository.CalibrationObservation value : observations) {
            canonical.append(value.id()).append('|')
                    .append(Double.toHexString(value.rawProbability())).append('|')
                    .append(Double.toHexString(value.issuedProbability())).append('|')
                    .append(value.outcome().name()).append('|')
                    .append(value.dueOn()).append('|')
                    .append(value.resolvedAt()).append('\n');
        }
        return PredictionFingerprint.sha256(canonical.toString());
    }

    private static List<PavaCalibrator.Sample> samples(
            List<PredictionRepository.CalibrationObservation> observations) {
        return observations.stream().map(value -> new PavaCalibrator.Sample(
                value.rawProbability(), value.outcomeBinary())).toList();
    }

    private static int quarterKey(
            PredictionRepository.CalibrationObservation observation) {
        var date = observation.resolvedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return date.getYear() * 4 + (date.getMonthValue() - 1) / 3;
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue)
                .average().orElseThrow();
    }

    private static double square(double value) {
        return value * value;
    }

    public record Result(
            PredictionRepository.StoredCalibration calibration,
            List<PredictionRepository.StoredDriftAlert> alerts,
            boolean reused,
            boolean issuanceFrozen) {

        public Result {
            calibration = Objects.requireNonNull(calibration, "calibration");
            alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
            if (issuanceFrozen
                    != alerts.stream().anyMatch(
                            PredictionRepository.StoredDriftAlert
                                    ::freezeIssuance)
                    && !alerts.isEmpty()) {
                throw new IllegalArgumentException(
                        "calibration result freeze state is inconsistent");
            }
        }
    }
}
