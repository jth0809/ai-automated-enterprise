package com.aienterprise.backend.tracker.prediction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure coordinator; automatic and admin execution gates are added separately. */
public class PredictionIssuanceService {

    private final PredictionInputFactory inputs;
    private final PredictionRepository repository;
    private final CalibrationSource calibrations;
    private final HazardEstimator estimator;

    public PredictionIssuanceService(
            PredictionInputFactory inputs,
            PredictionRepository repository,
            CalibrationSource calibrations) {
        this(inputs, repository, calibrations, new HazardEstimator());
    }

    PredictionIssuanceService(
            PredictionInputFactory inputs,
            PredictionRepository repository,
            CalibrationSource calibrations,
            HazardEstimator estimator) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.calibrations = Objects.requireNonNull(calibrations, "calibrations");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    public PredictionRepository.StoredCohort issue() {
        PredictionInputFactory.Input input = inputs.create();
        PredictionCandidateSelector.Calibration calibration =
                calibrations.current();
        HazardEstimator.Result hazards = estimator.estimate(
                input.nodes(), input.claims(), input.asOf(),
                input.dormancyTriggerYears(), input.hazardParameters());
        List<PredictionCandidateSelector.Candidate> candidates =
                new PredictionCandidateSelector(input.hazardParameters())
                        .select(hazards.nodes(), input.issuedOn(), calibration);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "no eligible micro-prediction candidates");
        }
        PredictionFingerprint.Value fingerprint = PredictionFingerprint.of(
                input, calibration, candidates);
        List<PredictionRepository.PublishedCandidate> published =
                new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            published.add(new PredictionRepository.PublishedCandidate(
                    candidates.get(i), fingerprint.candidateSha256().get(i),
                    fingerprint.statementSha256().get(i)));
        }
        return repository.saveCompleted(new PredictionRepository.CohortDraft(
                fingerprint.cohortKey(), fingerprint.inputSha256(),
                input.datasetSha256(), input.nodeSetVersion(),
                input.rubricVersion(), input.hazardParameters().version(),
                calibration.version(), input.asOf(), input.issuedOn(),
                published));
    }

    @FunctionalInterface
    public interface CalibrationSource {
        PredictionCandidateSelector.Calibration current();
    }
}
