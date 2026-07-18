package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class PredictionCalibrationRepositoryTest {

    @Autowired
    private PredictionRepository repository;

    @Test
    void calibrationInputIsIdempotentAndOnlyLatestResultIsCurrent() {
        var identity = draft("a", PredictionRepository.CalibrationMethod.IDENTITY,
                PredictionRepository.CalibrationStatus
                        .INSUFFICIENT_CALIBRATION_DATA, "[]");

        var first = repository.saveCalibration(identity);
        var repeated = repository.saveCalibration(identity);
        var pava = repository.saveCalibration(draft(
                "b", PredictionRepository.CalibrationMethod.PAVA,
                PredictionRepository.CalibrationStatus.OK,
                "[{\"x\":0.2,\"y\":0.3}]") );

        assertFalse(first.reused());
        assertTrue(repeated.reused());
        assertEquals(first.calibration().id(), repeated.calibration().id());
        assertEquals(pava.calibration(),
                repository.findCurrentCalibration().orElseThrow());
    }

    @Test
    void driftAlertsAreIdempotentAndExposeAFreezeWithoutStructuralWrites() {
        var calibration = repository.saveCalibration(draft(
                "c", PredictionRepository.CalibrationMethod.PAVA,
                PredictionRepository.CalibrationStatus.OK,
                "[{\"x\":0.2,\"y\":0.3}]")).calibration();
        var alert = new PredictionRepository.DriftAlertDraft(
                PredictionDriftDetector.AlertCode.CALIBRATION_IN_LARGE,
                0.30, 0.15, 0.25,
                PredictionDriftDetector.Severity.FREEZE, true,
                PredictionFingerprint.sha256("drift-c"));

        var first = repository.saveDriftAlerts(
                calibration.calibrationVersion(), List.of(alert));
        var repeated = repository.saveDriftAlerts(
                calibration.calibrationVersion(), List.of(alert));

        assertEquals(first, repeated);
        assertTrue(repository.isIssuanceFrozen(
                calibration.calibrationVersion()));
        assertEquals("hazard-v1", repository.loadActiveParameters().version());
    }

    @Test
    void operationsStatusHandlesAnInitializedButEmptyTrackRecord() {
        var calibration = repository.saveCalibration(draft(
                "d", PredictionRepository.CalibrationMethod.IDENTITY,
                PredictionRepository.CalibrationStatus
                        .INSUFFICIENT_CALIBRATION_DATA, "[]")).calibration();

        PredictionRepository.OperationsStatus status = repository
                .operationsStatus(LocalDate.of(2026, 7, 16));

        assertEquals(0, status.completedCohorts());
        assertEquals(0, status.pendingPredictions());
        assertEquals(calibration.calibrationVersion(),
                status.currentCalibrationVersion());
        assertFalse(status.issuanceFrozen());
    }

    private static PredictionRepository.CalibrationDraft draft(
            String seed,
            PredictionRepository.CalibrationMethod method,
            PredictionRepository.CalibrationStatus status,
            String knots) {
        boolean pava = method == PredictionRepository.CalibrationMethod.PAVA;
        return new PredictionRepository.CalibrationDraft(
                "calibration-" + seed, hex(seed), method, status,
                pava ? 30 : 0, pava ? 4 : 0, knots,
                pava ? 0.25 : null, pava ? 0.20 : null,
                pava ? 0.05 : null, "fixture");
    }

    private static String hex(String seed) {
        return seed.repeat(64).substring(0, 64);
    }
}
