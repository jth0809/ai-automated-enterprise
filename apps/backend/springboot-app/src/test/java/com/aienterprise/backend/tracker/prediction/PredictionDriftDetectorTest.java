package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PredictionDriftDetectorTest {

    @Test
    void remainsInactiveUntilBothPredeclaredGatesAreMet() {
        List<PredictionRepository.CalibrationObservation> observations =
                observations(29, 4, 0.9, 0);

        PredictionDriftDetector.Result result = new PredictionDriftDetector()
                .detect(observations, HazardParameters.defaults());

        assertFalse(result.eligible());
        assertTrue(result.alerts().isEmpty());
        assertFalse(result.freezeIssuance());
    }

    @Test
    void extremeBiasFreezesIssuanceButDoesNotMutateStructuralSettings() {
        HazardParameters parameters = HazardParameters.defaults();
        List<PredictionRepository.CalibrationObservation> observations =
                observations(40, 4, 0.9, 0);

        PredictionDriftDetector.Result result = new PredictionDriftDetector()
                .detect(observations, parameters);

        assertTrue(result.eligible());
        assertEquals(0.9, result.calibrationInLarge(), 1e-12);
        assertTrue(result.freezeIssuance());
        assertTrue(result.alerts().stream().anyMatch(alert ->
                alert.code() == PredictionDriftDetector.AlertCode.CALIBRATION_IN_LARGE
                        && alert.severity()
                        == PredictionDriftDetector.Severity.FREEZE));
        assertEquals(HazardParameters.defaults(), parameters);
    }

    @Test
    void recentBrierDeteriorationUsesTheLatestResolvedQuarter() {
        List<PredictionRepository.CalibrationObservation> observations =
                new ArrayList<>();
        observations.addAll(observations(30, 3, 0.9, 1));
        observations.addAll(observationsFrom(
                31, 10, LocalDate.of(2026, 10, 1), 0.9, 0));

        PredictionDriftDetector.Result result = new PredictionDriftDetector()
                .detect(observations, HazardParameters.defaults());

        assertEquals(0.8, result.brierDeterioration(), 1e-12);
        assertTrue(result.alerts().stream().anyMatch(alert ->
                alert.code() == PredictionDriftDetector.AlertCode.BRIER_DETERIORATION
                        && alert.severity()
                        == PredictionDriftDetector.Severity.FREEZE));
    }

    private static List<PredictionRepository.CalibrationObservation> observations(
            int count, int quarters, double probability, int outcome) {
        List<PredictionRepository.CalibrationObservation> values =
                new ArrayList<>();
        for (int index = 0; index < count; index++) {
            LocalDate resolved = LocalDate.of(2026, 1, 15)
                    .plusMonths(3L * (index % quarters));
            values.add(observation(index + 1, resolved, probability, outcome));
        }
        return values;
    }

    private static List<PredictionRepository.CalibrationObservation> observationsFrom(
            int firstId, int count, LocalDate resolved,
            double probability, int outcome) {
        List<PredictionRepository.CalibrationObservation> values =
                new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(observation(firstId + index, resolved.plusDays(index),
                    probability, outcome));
        }
        return values;
    }

    private static PredictionRepository.CalibrationObservation observation(
            long id, LocalDate resolved, double probability, int outcome) {
        return new PredictionRepository.CalibrationObservation(
                id, probability, probability,
                outcome == 1 ? PredictionRepository.Outcome.HIT
                        : PredictionRepository.Outcome.MISS,
                resolved.minusDays(1), resolved.atStartOfDay()
                        .toInstant(ZoneOffset.UTC));
    }
}
