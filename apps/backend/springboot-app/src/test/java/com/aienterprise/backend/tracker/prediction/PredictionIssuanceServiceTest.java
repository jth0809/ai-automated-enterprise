package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aienterprise.backend.tracker.backfill.BackfillClaim;
import com.aienterprise.backend.tracker.backfill.BackfillReview;
import com.aienterprise.backend.tracker.backfill.ProgramEndEffect;
import com.aienterprise.backend.tracker.domain.NodeRow;

class PredictionIssuanceServiceTest {

    @Test
    void calculatesAStableAuditableCohortBeforePersistence() {
        PredictionInputFactory inputs = mock(PredictionInputFactory.class);
        PredictionRepository repository = mock(PredictionRepository.class);
        PredictionIssuanceService.CalibrationSource calibration =
                mock(PredictionIssuanceService.CalibrationSource.class);
        PredictionInputFactory.Input input = input(List.of(), node(1, 0));
        when(inputs.create()).thenReturn(input);
        when(calibration.current()).thenReturn(
                new PredictionCandidateSelector.Calibration(
                        "calibration-v1-test", value -> value));
        PredictionRepository.StoredCohort stored = mock(
                PredictionRepository.StoredCohort.class);
        when(repository.saveCompleted(any())).thenReturn(stored);
        PredictionIssuanceService service = new PredictionIssuanceService(
                inputs, repository, calibration);

        assertEquals(stored, service.issue());
        assertEquals(stored, service.issue());

        ArgumentCaptor<PredictionRepository.CohortDraft> captor =
                ArgumentCaptor.forClass(PredictionRepository.CohortDraft.class);
        verify(repository, org.mockito.Mockito.times(2))
                .saveCompleted(captor.capture());
        assertEquals(captor.getAllValues().getFirst().inputSha256(),
                captor.getAllValues().getLast().inputSha256());
        assertEquals(1, captor.getValue().predictions().size());
        assertEquals("LOW_INFORMATION", captor.getValue().predictions()
                .getFirst().candidate().informationStatus().name());
    }

    @Test
    void refusesToPublishWhenNoActiveNodeBelowLevelEightExists() {
        PredictionInputFactory inputs = mock(PredictionInputFactory.class);
        PredictionRepository repository = mock(PredictionRepository.class);
        PredictionIssuanceService.CalibrationSource calibration =
                mock(PredictionIssuanceService.CalibrationSource.class);
        when(inputs.create()).thenReturn(input(
                List.of(claim("P1-A", 8)), node(1, 8)));
        when(calibration.current()).thenReturn(
                PredictionCandidateSelector.Calibration.identity());
        PredictionIssuanceService service = new PredictionIssuanceService(
                inputs, repository, calibration);

        assertEquals("no eligible micro-prediction candidates",
                assertThrows(IllegalStateException.class, service::issue)
                        .getMessage());
        verify(repository, never()).saveCompleted(any());
    }

    private static PredictionInputFactory.Input input(
            List<BackfillClaim> claims,
            NodeRow node) {
        return new PredictionInputFactory.Input(
                "a".repeat(64), "nodes-v1.0", "r2.0",
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 16),
                List.of(node), claims, 15, HazardParameters.defaults());
    }

    private static NodeRow node(long id, int level) {
        return new NodeRow(
                id, "P1-A", 1, "Node A", "TRL", level, "OFFICIAL",
                "ACTIVE", null, null, 1.0, false,
                "fixture", "nodes-v1.0");
    }

    private static BackfillClaim claim(String nodeCode, int level) {
        return new BackfillClaim(
                "BF-A", "HC-A", "nodes-v1.0", "r2.0", nodeCode,
                "FLIGHT_TEST", level, "Actor", LocalDate.of(2020, 1, 1),
                "DAY", "OFFICIAL", "Event", "Fixture.",
                ProgramEndEffect.NONE, null, List.of("HC-A#SRC"),
                new BackfillReview("APPROVED", "APPROVED", "Reviewed."));
    }
}
