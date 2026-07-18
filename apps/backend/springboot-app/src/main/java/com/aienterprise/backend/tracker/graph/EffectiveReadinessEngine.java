package com.aienterprise.backend.tracker.graph;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.math.Params;
import com.aienterprise.backend.tracker.math.Readiness;

public class EffectiveReadinessEngine {

    private static final double TIE_TOLERANCE = 1e-12;

    private final CapabilityGraphValidator validator;

    public EffectiveReadinessEngine() {
        this(new CapabilityGraphValidator());
    }

    EffectiveReadinessEngine(CapabilityGraphValidator validator) {
        this.validator = validator;
    }

    public ReadinessResult calculate(
            List<NodeRow> nodeRows,
            CapabilityGraph graph,
            Params params,
            LocalDate asOf) {
        return prepare(nodeRows, graph).calculate(nodeRows, params, asOf);
    }

    public Prepared prepare(
            List<NodeRow> nodeRows,
            CapabilityGraph graph) {
        Objects.requireNonNull(graph, "graph");
        validator.validate(graph, nodeRows);
        Set<String> expectedCodes = new HashSet<>();
        nodeRows.forEach(node -> expectedCodes.add(node.code()));
        return new Prepared(graph, Set.copyOf(expectedCodes));
    }

    private ReadinessResult calculateValidated(
            List<NodeRow> nodeRows,
            CapabilityGraph graph,
            Params params,
            LocalDate asOf) {
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(asOf, "asOf");

        List<NodeRow> sortedNodes = nodeRows.stream()
                .sorted(Comparator.comparing(NodeRow::code))
                .toList();
        Map<String, Double> rawByCode = new LinkedHashMap<>();
        for (NodeRow node : sortedNodes) {
            LocalDate dormancyOrigin = node.programEndDate() == null
                    ? node.dormantSince()
                    : node.programEndDate();
            double raw = Readiness.nodeReadiness(
                    node.currentLevel(),
                    "DORMANT".equals(node.nodeStatus()),
                    dormancyOrigin,
                    node.scaleType(),
                    params,
                    asOf);
            rawByCode.put(node.code(), raw);
        }

        Map<String, List<CapabilityEdgeRow>> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (NodeRow node : sortedNodes) {
            incoming.put(node.code(), new ArrayList<>());
            outgoing.put(node.code(), new ArrayList<>());
            indegree.put(node.code(), 0);
        }
        for (CapabilityEdgeRow edge : graph.edges()) {
            incoming.get(edge.toCode()).add(edge);
            outgoing.get(edge.fromCode()).add(edge.toCode());
            indegree.compute(edge.toCode(), (ignored, degree) -> degree + 1);
        }

        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((code, degree) -> {
            if (degree == 0) {
                ready.add(code);
            }
        });

        Map<String, NodeReadinessResult> results = new LinkedHashMap<>();
        while (!ready.isEmpty()) {
            String nodeCode = ready.remove();
            double raw = rawByCode.get(nodeCode);
            NodeReadinessResult result = evaluateNode(
                    nodeCode, raw, incoming.get(nodeCode), results);
            results.put(nodeCode, result);

            List<String> targets = new ArrayList<>(outgoing.get(nodeCode));
            targets.sort(String::compareTo);
            for (String target : targets) {
                int remaining = indegree.compute(target, (ignored, degree) -> degree - 1);
                if (remaining == 0) {
                    ready.add(target);
                }
            }
        }
        if (results.size() != sortedNodes.size()) {
            throw new IllegalStateException("validated graph did not produce a full topological order");
        }

        Map<Integer, Double> rawPillars = new TreeMap<>();
        Map<Integer, Double> effectivePillars = new TreeMap<>();
        for (NodeRow node : sortedNodes) {
            NodeReadinessResult result = results.get(node.code());
            rawPillars.merge(
                    node.pillar(), node.weight() * result.rawReadiness(), Double::sum);
            effectivePillars.merge(
                    node.pillar(), node.weight() * result.effectiveReadiness(), Double::sum);
        }

        return new ReadinessResult(
                graph.version(), results, rawPillars, effectivePillars);
    }

    public final class Prepared {

        private final CapabilityGraph graph;
        private final Set<String> expectedCodes;

        private Prepared(CapabilityGraph graph, Set<String> expectedCodes) {
            this.graph = graph;
            this.expectedCodes = expectedCodes;
        }

        public ReadinessResult calculate(
                List<NodeRow> nodeRows,
                Params params,
                LocalDate asOf) {
            requireMatchingRegistry(nodeRows);
            return calculateValidated(nodeRows, graph, params, asOf);
        }

        private void requireMatchingRegistry(List<NodeRow> nodeRows) {
            if (nodeRows == null || nodeRows.size() != expectedCodes.size()) {
                throw new IllegalArgumentException(
                        "prepared readiness node registry changed");
            }
            Set<String> actualCodes = new HashSet<>();
            for (NodeRow node : nodeRows) {
                if (node == null
                        || !graph.nodeSetVersion().equals(node.nodeSetVersion())
                        || !expectedCodes.contains(node.code())
                        || !actualCodes.add(node.code())) {
                    throw new IllegalArgumentException(
                            "prepared readiness node registry changed");
                }
            }
        }
    }

    private static NodeReadinessResult evaluateNode(
            String nodeCode,
            double raw,
            List<CapabilityEdgeRow> incoming,
            Map<String, NodeReadinessResult> completed) {
        if (incoming.isEmpty()) {
            return new NodeReadinessResult(
                    nodeCode, raw, raw, null, List.of(), List.of());
        }

        Map<Integer, List<CapabilityEdgeRow>> groups = new TreeMap<>();
        for (CapabilityEdgeRow edge : incoming) {
            groups.computeIfAbsent(edge.orGroup(), ignored -> new ArrayList<>()).add(edge);
        }
        groups.values().forEach(edges -> edges.sort(
                Comparator.comparing(CapabilityEdgeRow::fromCode)));

        Map<Integer, Double> groupCaps = new TreeMap<>();
        for (Map.Entry<Integer, List<CapabilityEdgeRow>> group : groups.entrySet()) {
            double cap = 0;
            for (CapabilityEdgeRow edge : group.getValue()) {
                NodeReadinessResult predecessor = completed.get(edge.fromCode());
                if (predecessor == null) {
                    throw new IllegalStateException(
                            "predecessor was not evaluated: " + edge.fromCode());
                }
                cap = Math.max(cap, Math.min(
                        1.0, predecessor.effectiveReadiness() + edge.deltaE()));
            }
            groupCaps.put(group.getKey(), cap);
        }

        double dependencyCap = groupCaps.values().stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElseThrow();
        List<Integer> limitingGroups = groupCaps.entrySet().stream()
                .filter(entry -> approximatelyEqual(entry.getValue(), dependencyCap))
                .map(Map.Entry::getKey)
                .toList();

        List<String> limitingDependencies = new ArrayList<>();
        for (int group : limitingGroups) {
            double groupCap = groupCaps.get(group);
            for (CapabilityEdgeRow edge : groups.get(group)) {
                double edgeCap = Math.min(
                        1.0,
                        completed.get(edge.fromCode()).effectiveReadiness() + edge.deltaE());
                if (approximatelyEqual(edgeCap, groupCap)) {
                    limitingDependencies.add(edge.fromCode());
                }
            }
        }
        limitingDependencies.sort(String::compareTo);

        return new NodeReadinessResult(
                nodeCode,
                raw,
                Math.min(raw, dependencyCap),
                dependencyCap,
                limitingGroups,
                limitingDependencies);
    }

    private static boolean approximatelyEqual(double left, double right) {
        return Math.abs(left - right) <= TIE_TOLERANCE;
    }
}
