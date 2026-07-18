package com.aienterprise.backend.tracker.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.math.Params;

class EffectiveReadinessEngineTest {

    private static final String GRAPH_VERSION = "graph-test-v1";
    private static final String NODE_SET_VERSION = "nodes-test-v1";
    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 16);
    private static final double TOLERANCE = 1e-12;

    private final EffectiveReadinessEngine engine = new EffectiveReadinessEngine();
    private final Params params = testParams();

    @Test
    void mandatoryAndReproducesPointThreePlusPointOneFiveCap() {
        List<NodeRow> nodes = List.of(
                node("A", 1, 0.2),
                node("B", 2, 0.3),
                node("TARGET", 3, 0.5));
        CapabilityGraph graph = graph(List.of(
                edge("A", "TARGET", 1, 0.15),
                edge("B", "TARGET", 2, 0.15)));

        ReadinessResult result = engine.calculate(nodes, graph, params, AS_OF);
        NodeReadinessResult target = result.nodes().get("TARGET");

        assertEquals(0.90, target.rawReadiness(), TOLERANCE);
        assertEquals(0.45, target.dependencyCap(), TOLERANCE);
        assertEquals(0.45, target.effectiveReadiness(), TOLERANCE);
        assertEquals(List.of(1), target.limitingGroups());
        assertEquals(List.of("A"), target.limitingDependencies());
    }

    @Test
    void alternativesWithinOneGroupUseOrMaximum() {
        List<NodeRow> nodes = List.of(
                node("A", 1, 0.2),
                node("B", 2, 0.3),
                node("TARGET", 3, 0.5));
        CapabilityGraph graph = graph(List.of(
                edge("A", "TARGET", 1, 0.15),
                edge("B", "TARGET", 1, 0.15)));

        NodeReadinessResult target = engine.calculate(nodes, graph, params, AS_OF)
                .nodes().get("TARGET");

        assertEquals(0.75, target.dependencyCap(), TOLERANCE);
        assertEquals(0.75, target.effectiveReadiness(), TOLERANCE);
        assertEquals(List.of(1), target.limitingGroups());
        assertEquals(List.of("B"), target.limitingDependencies());
    }

    @Test
    void effectiveCapsPropagateAcrossTwoHopChain() {
        List<NodeRow> nodes = List.of(
                node("A", 1, 0.2),
                node("B", 3, 0.3),
                node("C", 3, 0.5));
        CapabilityGraph graph = graph(List.of(
                edge("A", "B", 1, 0.15),
                edge("B", "C", 1, 0.15)));

        ReadinessResult result = engine.calculate(nodes, graph, params, AS_OF);

        assertEquals(0.30, result.nodes().get("A").effectiveReadiness(), TOLERANCE);
        assertEquals(0.45, result.nodes().get("B").effectiveReadiness(), TOLERANCE);
        assertEquals(0.60, result.nodes().get("C").effectiveReadiness(), TOLERANCE);
    }

    @Test
    void dependencyNeverRaisesRawReadinessAndUnconnectedNodeKeepsPartialCredit() {
        List<NodeRow> nodes = List.of(
                node("SOURCE", 3, 0.25),
                node("LOW", 1, 0.25),
                node("PARTIAL", 2, 0.50));
        CapabilityGraph graph = graph(List.of(
                edge("SOURCE", "LOW", 1, 0.15)));

        ReadinessResult result = engine.calculate(nodes, graph, params, AS_OF);

        assertEquals(0.30, result.nodes().get("LOW").effectiveReadiness(), TOLERANCE);
        assertEquals(0.60, result.nodes().get("PARTIAL").effectiveReadiness(), TOLERANCE);
        assertEquals(null, result.nodes().get("PARTIAL").dependencyCap());
        result.nodes().values().forEach(node -> assertTrue(
                node.effectiveReadiness() <= node.rawReadiness() + TOLERANCE));
    }

    @Test
    void computesRawAndEffectiveWeightedPillarsWithoutMutatingInputs() {
        List<NodeRow> nodes = new ArrayList<>(List.of(
                node("A", 1, 0.2),
                node("B", 2, 0.3),
                node("TARGET", 3, 0.5)));
        List<NodeRow> before = List.copyOf(nodes);
        CapabilityGraph graph = graph(List.of(
                edge("A", "TARGET", 1, 0.15),
                edge("B", "TARGET", 2, 0.15)));

        ReadinessResult result = engine.calculate(nodes, graph, params, AS_OF);

        assertEquals(0.69, result.rawPillarReadiness().get(1), TOLERANCE);
        assertEquals(0.465, result.effectivePillarReadiness().get(1), TOLERANCE);
        assertEquals(before, nodes);
        assertThrows(UnsupportedOperationException.class,
                () -> result.nodes().put("X", result.nodes().get("A")));
    }

    @Test
    void resultIsDeterministicWhenNodeAndEdgeInputOrderChanges() {
        List<NodeRow> nodes = new ArrayList<>(List.of(
                node("A", 1, 0.2),
                node("B", 2, 0.3),
                node("TARGET", 3, 0.5)));
        List<CapabilityEdgeRow> edges = new ArrayList<>(List.of(
                edge("A", "TARGET", 1, 0.15),
                edge("B", "TARGET", 2, 0.15)));
        ReadinessResult ordered = engine.calculate(nodes, graph(edges), params, AS_OF);

        Collections.reverse(nodes);
        Collections.reverse(edges);
        ReadinessResult reversed = engine.calculate(nodes, graph(edges), params, AS_OF);

        assertEquals(ordered, reversed);
    }

    @Test
    void preparedGraphReusesValidationForDynamicNodeLevelsAndWeights() {
        List<NodeRow> nodes = List.of(
                node("A", 1, 0.2),
                node("B", 2, 0.3),
                node("TARGET", 3, 0.5));
        CapabilityGraph graph = graph(List.of(
                edge("A", "TARGET", 1, 0.15),
                edge("B", "TARGET", 2, 0.15)));
        EffectiveReadinessEngine.Prepared prepared = engine.prepare(nodes, graph);
        List<NodeRow> changed = List.of(
                node("A", 2, 0.4),
                node("B", 2, 0.2),
                node("TARGET", 3, 0.4));

        assertEquals(engine.calculate(changed, graph, params, AS_OF),
                prepared.calculate(changed, params, AS_OF));
        assertThrows(IllegalArgumentException.class, () -> prepared.calculate(
                List.of(node("UNKNOWN", 1, 1.0)), params, AS_OF));
    }

    private static NodeRow node(String code, int level, double weight) {
        return new NodeRow(
                Math.abs(code.hashCode()), code, 1, code, "TRL", level,
                "OFFICIAL", "ACTIVE", null, null, weight, false,
                "test node " + code, NODE_SET_VERSION);
    }

    private static CapabilityEdgeRow edge(
            String fromCode, String toCode, int orGroup, double deltaE) {
        return new CapabilityEdgeRow(
                GRAPH_VERSION, fromCode, toCode, orGroup, deltaE);
    }

    private static CapabilityGraph graph(List<CapabilityEdgeRow> edges) {
        CapabilityGraph draft = new CapabilityGraph(
                GRAPH_VERSION, NODE_SET_VERSION, "0".repeat(64), edges.size(), edges);
        return new CapabilityGraph(
                GRAPH_VERSION, NODE_SET_VERSION, draft.computedSha256(), edges.size(), edges);
    }

    private static Params testParams() {
        Map<Integer, Double> levels = new LinkedHashMap<>();
        levels.put(1, 0.30);
        levels.put(2, 0.60);
        levels.put(3, 0.90);
        for (int level = 4; level <= 9; level++) {
            levels.put(level, 1.00);
        }
        return new Params(
                "params-test", 0.01, 4.0, 6, 10, 4, 15,
                0.85, 0.15, 0.40, 15, 0.15,
                2, 150, 90, 20.0, levels, levels);
    }
}
