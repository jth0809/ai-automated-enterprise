package com.aienterprise.backend.tracker.graph;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.aienterprise.backend.tracker.domain.NodeRow;

public class CapabilityGraphValidator {

    public void validate(CapabilityGraph graph, Collection<NodeRow> nodes) {
        requireText(graph.version(), "graph version");
        requireText(graph.nodeSetVersion(), "node-set version");
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException("capability graph requires at least one node");
        }

        Map<String, NodeRow> nodesByCode = new HashMap<>();
        for (NodeRow node : nodes) {
            requireText(node.code(), "node code");
            if (nodesByCode.put(node.code(), node) != null) {
                throw new IllegalStateException("duplicate node code: " + node.code());
            }
            if (!graph.nodeSetVersion().equals(node.nodeSetVersion())) {
                throw new IllegalStateException(
                        "node-set version mismatch for " + node.code());
            }
        }

        if (graph.declaredEdgeCount() != graph.edges().size()) {
            throw new IllegalStateException("declared graph edge count does not match rows");
        }
        validateDeclaredHash(graph);

        Set<String> edgePairs = new HashSet<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        nodesByCode.keySet().forEach(code -> {
            indegree.put(code, 0);
            outgoing.put(code, new ArrayList<>());
        });

        for (CapabilityEdgeRow edge : graph.edges()) {
            if (!graph.version().equals(edge.graphVersion())) {
                throw new IllegalStateException("edge graph version mismatch");
            }
            requireText(edge.fromCode(), "edge source code");
            requireText(edge.toCode(), "edge target code");
            if (!nodesByCode.containsKey(edge.fromCode())
                    || !nodesByCode.containsKey(edge.toCode())) {
                throw new IllegalStateException(
                        "edge references an unknown node: " + edge.fromCode()
                                + " -> " + edge.toCode());
            }
            if (edge.fromCode().equals(edge.toCode())) {
                throw new IllegalStateException("self dependency is forbidden: " + edge.fromCode());
            }
            if (edge.orGroup() < 1 || edge.orGroup() > 99) {
                throw new IllegalStateException("edge OR group must be between 1 and 99");
            }
            if (!Double.isFinite(edge.deltaE()) || edge.deltaE() < 0 || edge.deltaE() > 0.5) {
                throw new IllegalStateException("edge delta_e must be finite and between 0 and 0.5");
            }
            String pair = edge.fromCode() + "\u0000" + edge.toCode();
            if (!edgePairs.add(pair)) {
                throw new IllegalStateException(
                        "duplicate dependency edge: " + edge.fromCode() + " -> " + edge.toCode());
            }
            outgoing.get(edge.fromCode()).add(edge.toCode());
            indegree.compute(edge.toCode(), (code, degree) -> degree + 1);
        }

        assertAcyclic(indegree, outgoing);
    }

    private static void validateDeclaredHash(CapabilityGraph graph) {
        if (!graph.declaredSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("declared graph SHA-256 must be lowercase hexadecimal");
        }
        byte[] declared = HexFormat.of().parseHex(graph.declaredSha256());
        byte[] computed = HexFormat.of().parseHex(graph.computedSha256());
        if (!MessageDigest.isEqual(declared, computed)) {
            throw new IllegalStateException("declared graph SHA-256 does not match canonical rows");
        }
    }

    private static void assertAcyclic(
            Map<String, Integer> originalIndegree,
            Map<String, List<String>> outgoing) {
        Map<String, Integer> indegree = new HashMap<>(originalIndegree);
        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((code, degree) -> {
            if (degree == 0) {
                ready.add(code);
            }
        });

        int visited = 0;
        while (!ready.isEmpty()) {
            String code = ready.remove();
            visited++;
            List<String> targets = new ArrayList<>(outgoing.get(code));
            targets.sort(String::compareTo);
            for (String target : targets) {
                int remaining = indegree.compute(target, (ignored, degree) -> degree - 1);
                if (remaining == 0) {
                    ready.add(target);
                }
            }
        }
        if (visited != indegree.size()) {
            throw new IllegalStateException("capability dependency graph contains a cycle");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " must not be blank");
        }
    }
}
