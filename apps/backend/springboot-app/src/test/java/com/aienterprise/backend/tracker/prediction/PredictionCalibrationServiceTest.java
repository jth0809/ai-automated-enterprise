package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PredictionCalibrationServiceTest {

    @Test
    void persistsIdentityUntilBothSampleAndQuarterGatesPass() {
        PredictionRepository repository = repositoryWith(
                observations(29, 4));
        PredictionCalibrationService service =
                new PredictionCalibrationService(repository);

        PredictionCalibrationService.Result result = service.calibrate();

        assertEquals(PredictionRepository.CalibrationMethod.IDENTITY,
                result.calibration().method());
        assertEquals(PredictionRepository.CalibrationStatus
                        .INSUFFICIENT_CALIBRATION_DATA,
                result.calibration().status());
        assertEquals("[]", result.calibration().knotsJson());
        assertFalse(result.issuanceFrozen());
    }

    @Test
    void eligibleHistoryFitsFinalPavaAndExpandingTimeOosMetrics() {
        PredictionRepository repository = repositoryWith(
                observations(40, 4));
        PredictionCalibrationService service =
                new PredictionCalibrationService(repository);

        PredictionCalibrationService.Result result = service.calibrate();

        assertEquals(PredictionRepository.CalibrationMethod.PAVA,
                result.calibration().method());
        assertEquals(PredictionRepository.CalibrationStatus.OK,
                result.calibration().status());
        assertNotEquals("[]", result.calibration().knotsJson());
        assertNotNull(result.calibration().oosBrierRaw());
        assertNotNull(result.calibration().oosBrierCalibrated());
        assertNotNull(result.calibration().calibrationInLarge());
        ArgumentCaptor<List<PredictionRepository.DriftAlertDraft>> alerts =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveDriftAlerts(
                anyString(), alerts.capture());
    }

    private static PredictionRepository repositoryWith(
            List<PredictionRepository.CalibrationObservation> observations) {
        PredictionRepository repository = mock(PredictionRepository.class);
        when(repository.loadActiveParameters())
                .thenReturn(HazardParameters.defaults());
        when(repository.findCalibrationObservations())
                .thenReturn(observations);
        when(repository.saveCalibration(any())).thenAnswer(invocation -> {
            PredictionRepository.CalibrationDraft draft =
                    invocation.getArgument(0);
            return new PredictionRepository.CalibrationSaveResult(
                    new PredictionRepository.StoredCalibration(
                            1, draft.calibrationVersion(), draft.inputSha256(),
                            draft.method(), draft.status(), draft.sampleCount(),
                            draft.quarterCount(), draft.knotsJson(),
                            draft.oosBrierRaw(), draft.oosBrierCalibrated(),
                            draft.calibrationInLarge(), draft.diagnostics(), true,
                            Instant.parse("2026-07-16T00:00:00Z")), false);
        });
        when(repository.saveDriftAlerts(anyString(), anyList()))
                .thenReturn(List.of());
        when(repository.isIssuanceFrozen(anyString())).thenReturn(false);
        return repository;
    }

    private static List<PredictionRepository.CalibrationObservation> observations(
            int count, int quarters) {
        List<PredictionRepository.CalibrationObservation> values =
                new ArrayList<>();
        for (int index = 0; index < count; index++) {
            LocalDate resolved = LocalDate.of(2025, 1, 15)
                    .plusMonths(3L * (index % quarters));
            double raw = 0.1 + (index % 9) * 0.1;
            PredictionRepository.Outcome outcome = index % 3 == 0
                    ? PredictionRepository.Outcome.HIT
                    : PredictionRepository.Outcome.MISS;
            values.add(new PredictionRepository.CalibrationObservation(
                    index + 1, raw, raw, outcome, resolved.minusDays(1),
                    resolved.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        return values;
    }
}
