package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.backfill.BackfillClaim;
import com.aienterprise.backend.tracker.backfill.BackfillReview;
import com.aienterprise.backend.tracker.backfill.ProgramEndEffect;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.math.Params;

class HistoricalClaimReplayTest {

    @Test
    void replaysAdvanceRollbackProgramEndDormancyAndRestoration() {
        HistoricalClaimReplay replay = new HistoricalClaimReplay(
                List.of(node()), List.of(
                        claim("A", "INSTITUTIONAL_ADVANCE", 6,
                                LocalDate.of(1957, 1, 7), ProgramEndEffect.NONE),
                        claim("B", "ROLLBACK", 4,
                                LocalDate.of(1958, 1, 6), ProgramEndEffect.NONE),
                        claim("C", "PROGRAM_CANCELLATION", null,
                                LocalDate.of(1959, 1, 5),
                                ProgramEndEffect.CAPABILITY_PROGRAM_END),
                        claim("D", "INSTITUTIONAL_ADVANCE", 5,
                                LocalDate.of(1975, 1, 6), ProgramEndEffect.NONE)));

        assertEquals(6, replay.frame(
                LocalDate.of(1957, 12, 31), Params.defaults())
                .nodes().getFirst().currentLevel());
        assertEquals(4, replay.frame(
                LocalDate.of(1958, 12, 31), Params.defaults())
                .nodes().getFirst().currentLevel());

        HistoricalClaimReplay.Frame dormant = replay.frame(
                LocalDate.of(1974, 1, 5), Params.defaults());
        assertEquals("DORMANT", dormant.nodes().getFirst().nodeStatus());
        assertEquals(LocalDate.of(1974, 1, 5),
                dormant.nodes().getFirst().dormantSince());

        HistoricalClaimReplay.Frame restored = replay.frame(
                LocalDate.of(1975, 1, 6), Params.defaults());
        assertEquals(5, restored.nodes().getFirst().currentLevel());
        assertEquals("ACTIVE", restored.nodes().getFirst().nodeStatus());
        assertNull(restored.nodes().getFirst().programEndDate());
        assertEquals(List.of(
                "INSTITUTIONAL_ADVANCE", "ROLLBACK", "DORMANCY",
                "INSTITUTIONAL_ADVANCE"),
                restored.stateChanges().get(6).stream()
                        .map(change -> change.eventType()).toList());
    }

    @Test
    void futureClaimCannotAffectCutoffStateOrFeatures() {
        HistoricalClaimReplay replay = new HistoricalClaimReplay(
                List.of(node()), List.of(
                        claim("A", "INSTITUTIONAL_ADVANCE", 3,
                                LocalDate.of(2000, 1, 3), ProgramEndEffect.NONE),
                        claim("FUTURE", "INSTITUTIONAL_ADVANCE", 8,
                                LocalDate.of(2020, 1, 6), ProgramEndEffect.NONE)));

        HistoricalClaimReplay.Frame frame = replay.frame(
                LocalDate.of(2010, 1, 4), Params.defaults());

        assertEquals(3, frame.nodes().getFirst().currentLevel());
        assertEquals(1, frame.stateChanges().get(6).size());
        assertTrue(frame.stateChanges().get(6).stream().allMatch(change ->
                !change.occurredOn().isAfter(LocalDate.of(2010, 1, 4))));
    }

    @Test
    void weeklyHistoryStopsExactlyAtRequestedCutoff() {
        HistoricalClaimReplay replay = new HistoricalClaimReplay(
                List.of(node()), List.of(claim(
                        "A", "INSTITUTIONAL_ADVANCE", 3,
                        LocalDate.of(1957, 1, 7), ProgramEndEffect.NONE)));

        var history = replay.weeklyHistory(
                LocalDate.of(1957, 1, 21), Params.defaults(), emptyGraph());

        assertEquals(3, history.get(6).size());
        assertEquals(LocalDate.of(1957, 1, 21),
                history.get(6).getLast().snapshotDate());
        assertTrue(history.values().stream().flatMap(List::stream).allMatch(row ->
                !row.snapshotDate().isAfter(LocalDate.of(1957, 1, 21))));
    }

    private static NodeRow node() {
        return new NodeRow(
                1, "P6-FUNDING", 6, "Funding", "EGL", 9,
                "OFFICIAL", "ACTIVE", null, null,
                1.0, false, "fixture", "nodes-v1.0");
    }

    private static com.aienterprise.backend.tracker.graph.CapabilityGraph emptyGraph() {
        var draft = new com.aienterprise.backend.tracker.graph.CapabilityGraph(
                "graph-v1.0", "nodes-v1.0", "0".repeat(64), 0, List.of());
        return new com.aienterprise.backend.tracker.graph.CapabilityGraph(
                draft.version(), draft.nodeSetVersion(), draft.computedSha256(),
                0, List.of());
    }

    private static BackfillClaim claim(
            String id,
            String eventType,
            Integer level,
            LocalDate occurredOn,
            ProgramEndEffect effect) {
        return new BackfillClaim(
                "BF-" + id, "HC-" + id, "nodes-v1.0", "r2.0",
                "P6-FUNDING", eventType, level, "Actor", occurredOn,
                "DAY", "OFFICIAL", "Event " + id, "Fixture.", effect,
                effect == ProgramEndEffect.CAPABILITY_PROGRAM_END
                        ? "Representative lineage ended." : null,
                List.of("HC-" + id + "#SRC"),
                new BackfillReview("APPROVED", "APPROVED", "Reviewed."));
    }
}
