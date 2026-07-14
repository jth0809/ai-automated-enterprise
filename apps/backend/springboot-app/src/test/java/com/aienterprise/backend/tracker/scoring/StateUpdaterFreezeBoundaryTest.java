package com.aienterprise.backend.tracker.scoring;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ops.StateFreezeService;

@ExtendWith(MockitoExtension.class)
class StateUpdaterFreezeBoundaryTest {

    @Mock
    private TrackerRepository repository;

    @Mock
    private StateFreezeService freezeService;

    @Test
    void rechecksFreezeImmediatelyBeforeFinalStateAdvance() {
        EventRow event = new EventRow(
                42L, "key", 7L, "LAB_RESULT", 2, "Agency",
                LocalDate.parse("2026-07-01"), "OFFICIAL", "PROVISIONAL",
                LocalDate.parse("2026-09-29"), null, null, false, 3L);
        NodeRow node = new NodeRow(
                7L, "P2-LSS-CLOSED", 2, "폐쇄형 생명유지", "MATURITY",
                1, "OFFICIAL", "ACTIVE", null, null,
                1.0, false, "test node", "nodes-v1.0");
        when(freezeService.isFrozen()).thenReturn(false, true);
        when(repository.findNodeById(7L)).thenReturn(node);
        StateUpdater updater = new StateUpdater(repository, freezeService);

        updater.processEvent(event);

        verify(repository).recordEventScore(42L, 1.8, 1);
        verify(repository).insertReviewIfAbsent(42L, "CIRCUIT_BREAKER");
        verify(repository, never()).advanceNode(7L, 2, "OFFICIAL", 42L, 3L);
        verify(repository, never()).markEventConfirmed(42L);
    }
}
