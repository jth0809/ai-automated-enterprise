package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.aienterprise.backend.tracker.prediction.PredictionCalibrationService;
import com.aienterprise.backend.tracker.prediction.PredictionIssuanceService;
import com.aienterprise.backend.tracker.prediction.PredictionRepository;
import com.aienterprise.backend.tracker.prediction.PredictionResolutionService;

class PredictionAdminControllerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-16T03:00:00Z"), ZoneOffset.UTC);

    private PredictionIssuanceService issuance;
    private PredictionResolutionService resolution;
    private PredictionCalibrationService calibration;
    private PredictionRepository repository;
    private PredictionAdminController controller;

    @BeforeEach
    void setUp() {
        issuance = mock(PredictionIssuanceService.class);
        resolution = mock(PredictionResolutionService.class);
        calibration = mock(PredictionCalibrationService.class);
        repository = mock(PredictionRepository.class);
        controller = new PredictionAdminController(
                issuance, resolution, calibration, repository,
                "test-secret", false, false, CLOCK);
    }

    @Test
    void everyOperationFailsClosedBeforeCallingAService() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.issue("wrong").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.resolve(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.calibrate("wrong").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.status("wrong").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.voidPrediction(1, "wrong",
                        new PredictionAdminController.VoidRequest("audit"))
                        .getStatusCode());
        verifyNoInteractions(issuance, resolution, calibration, repository);
    }

    @Test
    void manualIssueResolveCalibrateAndStatusExposeBoundedAuditSummaries() {
        when(issuance.issue()).thenReturn(new PredictionRepository.StoredCohort(
                7, "cohort-7", "a".repeat(64), "b".repeat(64),
                "nodes-v1.0", "r2.0", "hazard-v1", "calibration-a",
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 16),
                List.of(), Instant.parse("2026-07-16T03:00:00Z"),
                Instant.parse("2026-07-16T03:00:01Z")));
        when(resolution.resolveDue()).thenReturn(
                new PredictionResolutionService.Summary(2, 1, 1, 0, 0));
        PredictionRepository.StoredCalibration stored = calibration();
        when(calibration.calibrate()).thenReturn(
                new PredictionCalibrationService.Result(
                        stored, List.of(), false, false));
        when(repository.operationsStatus(LocalDate.of(2026, 7, 16)))
                .thenReturn(new PredictionRepository.OperationsStatus(
                        1, 12, 10, 2, 0, 0, 0,
                        stored.calibrationVersion(), stored.status(), false));

        assertEquals(HttpStatus.OK,
                controller.issue("test-secret").getStatusCode());
        assertEquals(HttpStatus.OK,
                controller.resolve("test-secret").getStatusCode());
        assertEquals(HttpStatus.OK,
                controller.calibrate("test-secret").getStatusCode());
        var status = controller.status("test-secret");

        assertEquals(HttpStatus.OK, status.getStatusCode());
        assertEquals(false, status.getBody().automaticIssuanceEnabled());
        assertEquals(false, status.getBody().automaticResolutionEnabled());
        assertEquals(12, status.getBody().operations().pendingPredictions());
        assertEquals("calibration-a",
                status.getBody().operations().currentCalibrationVersion());
    }

    @Test
    void manualVoidRequiresAuditTextAndSurfacesImmutableConflicts() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.voidPrediction(
                3, "test-secret", new PredictionAdminController.VoidRequest(" "))
                .getStatusCode());
        verify(resolution, never()).voidUnadjudicable(3, " ");
        when(resolution.voidUnadjudicable(3, "predicate changed"))
                .thenReturn(new PredictionRepository.ResolutionResult(
                        PredictionRepository.ResolutionStatus.CONFLICT, 3,
                        PredictionRepository.Outcome.HIT, 0.09, 11L));

        var response = controller.voidPrediction(
                3, "test-secret",
                new PredictionAdminController.VoidRequest("predicate changed"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(11L, response.getBody().conflictId());
    }

    private static PredictionRepository.StoredCalibration calibration() {
        return new PredictionRepository.StoredCalibration(
                1, "calibration-a", "c".repeat(64),
                PredictionRepository.CalibrationMethod.IDENTITY,
                PredictionRepository.CalibrationStatus
                        .INSUFFICIENT_CALIBRATION_DATA,
                0, 0, "[]", null, null, null, "fixture", true,
                Instant.parse("2026-07-16T03:00:00Z"));
    }
}
