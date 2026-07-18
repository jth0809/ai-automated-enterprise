package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PredictionResolutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void resolvesConfirmedTargetCrossingAsHitAndAbsenceAsMiss() {
        PredictionRepository repository = mock(PredictionRepository.class);
        var hit = pending(1, 0.70, "P1-A");
        var miss = pending(2, 0.70, "P2-A");
        when(repository.findPendingDue(LocalDate.of(2026, 7, 16)))
                .thenReturn(List.of(hit, miss));
        when(repository.findFirstTargetTransition(hit))
                .thenReturn(Optional.of(new PredictionRepository.TargetTransition(
                        99, LocalDate.of(2026, 6, 1))));
        when(repository.findFirstTargetTransition(miss)).thenReturn(Optional.empty());
        when(repository.resolve(any())).thenAnswer(invocation -> {
            PredictionRepository.ResolutionDraft draft = invocation.getArgument(0);
            return new PredictionRepository.ResolutionResult(
                    PredictionRepository.ResolutionStatus.APPLIED,
                    draft.predictionId(), draft.outcome(),
                    draft.outcome() == PredictionRepository.Outcome.HIT ? 0.09 : 0.49,
                    null);
        });

        PredictionResolutionService.Summary summary =
                new PredictionResolutionService(repository, CLOCK).resolveDue();

        assertEquals(new PredictionResolutionService.Summary(2, 1, 1, 0, 0),
                summary);
        ArgumentCaptor<PredictionRepository.ResolutionDraft> captor =
                ArgumentCaptor.forClass(PredictionRepository.ResolutionDraft.class);
        verify(repository, org.mockito.Mockito.times(2)).resolve(captor.capture());
        var drafts = captor.getAllValues();
        assertEquals(PredictionRepository.Outcome.HIT, drafts.getFirst().outcome());
        assertEquals(99L, drafts.getFirst().outcomeEventId());
        assertEquals(PredictionRepository.Outcome.MISS, drafts.getLast().outcome());
        assertEquals(hit.dueOn(), drafts.getFirst().evidenceDate());
        assertEquals(miss.dueOn(), drafts.getLast().evidenceDate());
    }

    @Test
    void manualVoidRequiresAnExplicitUnadjudicablePredicateAudit() {
        PredictionRepository repository = mock(PredictionRepository.class);
        when(repository.findForResolution(3)).thenReturn(Optional.of(
                pending(3, 0.4, "P3-A")));
        when(repository.resolve(any())).thenReturn(
                new PredictionRepository.ResolutionResult(
                        PredictionRepository.ResolutionStatus.APPLIED, 3,
                        PredictionRepository.Outcome.VOID, null, null));
        PredictionResolutionService service =
                new PredictionResolutionService(repository, CLOCK);

        assertThrows(IllegalArgumentException.class,
                () -> service.voidUnadjudicable(3, " "));
        verify(repository, never()).resolve(any());

        service.voidUnadjudicable(
                3, "Successor registry removed the original predicate meaning.");

        ArgumentCaptor<PredictionRepository.ResolutionDraft> captor =
                ArgumentCaptor.forClass(PredictionRepository.ResolutionDraft.class);
        verify(repository).resolve(captor.capture());
        assertEquals(PredictionRepository.Outcome.VOID,
                captor.getValue().outcome());
        assertEquals("PREDICATE_UNADJUDICABLE",
                captor.getValue().reasonCode());
    }

    private static PredictionRepository.PendingPrediction pending(
            long id, double probability, String nodeCode) {
        return new PredictionRepository.PendingPrediction(
                id, 10 + id, nodeCode, false, 5, probability,
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 7, 1),
                "nodes-v1.0", "r2.0");
    }
}
