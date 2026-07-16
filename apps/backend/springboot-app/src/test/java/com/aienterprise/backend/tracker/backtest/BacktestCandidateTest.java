package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.graph.CapabilityEdgeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.math.Params;

class BacktestCandidateTest {

    @Test
    void registryContainsExactlyThePredeclaredTwentySevenCandidates() {
        List<BacktestCandidate> candidates = BacktestCandidate.registry();

        assertEquals("backtest-candidates-v1", BacktestCandidate.REGISTRY_VERSION);
        assertEquals(27, candidates.size());
        assertEquals(27, new HashSet<>(candidates).size());
        assertEquals(new BacktestCandidate(4, 2, .75), candidates.getFirst());
        assertEquals(new BacktestCandidate(8, 8, 1.25), candidates.getLast());
        assertTrue(candidates.contains(BacktestCandidate.DEFAULT));
    }

    @Test
    void appliesOnlyCommonMKShrinkAndSharedDeltaScale() {
        Params central = Params.defaults();
        CapabilityGraph graph = graph();
        BacktestCandidate candidate = new BacktestCandidate(8, 2, 1.25);

        Params adjusted = candidate.apply(central);
        CapabilityGraph adjustedGraph = candidate.apply(graph);

        assertEquals(8, adjusted.windowM());
        assertEquals(2, adjusted.kShrink());
        assertEquals(central.trlMap(), adjusted.trlMap());
        assertEquals(central.maturityMap(), adjusted.maturityMap());
        assertEquals(.1875, adjustedGraph.edges().getFirst().deltaE(), 1e-12);
        assertNotEquals(graph.declaredSha256(), adjustedGraph.declaredSha256());
        assertEquals(adjustedGraph.computedSha256(), adjustedGraph.declaredSha256());
    }

    @Test
    void rejectsCandidatesOutsideTheFrozenRegistry() {
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestCandidate(5, 4, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestCandidate(6, 3, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestCandidate(6, 4, 1.1));
    }

    @Test
    void defaultDistanceIsStableAndSymmetric() {
        assertEquals(0, BacktestCandidate.DEFAULT.defaultDistance(), 1e-12);
        assertEquals(
                new BacktestCandidate(4, 2, .75).defaultDistance(),
                new BacktestCandidate(8, 8, 1.25).defaultDistance(), 1e-12);
    }

    private static CapabilityGraph graph() {
        List<CapabilityEdgeRow> edges = List.of(new CapabilityEdgeRow(
                "graph-v1.0", "P1-A", "P1-B", 1, .15));
        CapabilityGraph draft = new CapabilityGraph(
                "graph-v1.0", "nodes-v1.0", "0".repeat(64), 1, edges);
        return new CapabilityGraph(
                draft.version(), draft.nodeSetVersion(), draft.computedSha256(),
                draft.declaredEdgeCount(), draft.edges());
    }
}
