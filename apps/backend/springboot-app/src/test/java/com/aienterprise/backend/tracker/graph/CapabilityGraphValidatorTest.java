package com.aienterprise.backend.tracker.graph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.NodeRow;

class CapabilityGraphValidatorTest {

    private static final String VERSION = "graph-test-v1";
    private static final String NODE_SET = "nodes-test-v1";

    private final CapabilityGraphValidator validator = new CapabilityGraphValidator();

    @Test
    void acceptsAValidAcyclicGraph() {
        List<CapabilityEdgeRow> edges = List.of(
                edge("A", "B", 1, 0.15),
                edge("B", "C", 1, 0.10));

        assertDoesNotThrow(() -> validator.validate(graph(edges), nodes("A", "B", "C")));
    }

    @Test
    void rejectsUnknownSelfDuplicateAndCyclicEdges() {
        assertInvalid(List.of(edge("UNKNOWN", "B", 1, 0.15)), nodes("A", "B"));
        assertInvalid(List.of(edge("A", "A", 1, 0.15)), nodes("A"));
        assertInvalid(List.of(
                edge("A", "B", 1, 0.15),
                edge("A", "B", 2, 0.20)), nodes("A", "B"));
        assertInvalid(List.of(
                edge("A", "B", 1, 0.15),
                edge("B", "A", 1, 0.15)), nodes("A", "B"));
    }

    @Test
    void rejectsInvalidGroupDeltaVersionAndNodeSet() {
        assertInvalid(List.of(edge("A", "B", 0, 0.15)), nodes("A", "B"));
        assertInvalid(List.of(edge("A", "B", 100, 0.15)), nodes("A", "B"));
        assertInvalid(List.of(edge("A", "B", 1, -0.001)), nodes("A", "B"));
        assertInvalid(List.of(edge("A", "B", 1, 0.501)), nodes("A", "B"));
        assertInvalid(List.of(edge("A", "B", 1, Double.NaN)), nodes("A", "B"));

        List<CapabilityEdgeRow> mixedVersion = List.of(
                new CapabilityEdgeRow("graph-other", "A", "B", 1, 0.15));
        assertInvalid(mixedVersion, nodes("A", "B"));

        CapabilityGraph graph = graph(List.of(edge("A", "B", 1, 0.15)));
        assertThrows(IllegalStateException.class,
                () -> validator.validate(graph, List.of(node(1, "A", "nodes-other"),
                        node(2, "B", "nodes-other"))));
    }

    @Test
    void rejectsDeclaredCountAndHashMismatch() {
        List<CapabilityEdgeRow> edges = List.of(edge("A", "B", 1, 0.15));
        CapabilityGraph valid = graph(edges);

        CapabilityGraph badCount = new CapabilityGraph(
                VERSION, NODE_SET, valid.declaredSha256(), 2, edges);
        assertThrows(IllegalStateException.class,
                () -> validator.validate(badCount, nodes("A", "B")));

        CapabilityGraph badHash = new CapabilityGraph(
                VERSION, NODE_SET, "0".repeat(64), 1, edges);
        assertThrows(IllegalStateException.class,
                () -> validator.validate(badHash, nodes("A", "B")));
    }

    @Test
    void rejectsDuplicateNodeCodesAndMissingGraphMetadata() {
        CapabilityGraph graph = graph(List.of());
        assertThrows(IllegalStateException.class,
                () -> validator.validate(graph, List.of(node(1, "A", NODE_SET),
                        node(2, "A", NODE_SET))));

        CapabilityGraph blankVersion = new CapabilityGraph(
                " ", NODE_SET, sha256(" \n" + NODE_SET), 0, List.of());
        assertThrows(IllegalStateException.class,
                () -> validator.validate(blankVersion, nodes("A")));
    }

    private void assertInvalid(List<CapabilityEdgeRow> edges, List<NodeRow> nodes) {
        assertThrows(IllegalStateException.class, () -> validator.validate(graph(edges), nodes));
    }

    private static CapabilityEdgeRow edge(
            String from, String to, int group, double deltaE) {
        return new CapabilityEdgeRow(VERSION, from, to, group, deltaE);
    }

    private static CapabilityGraph graph(List<CapabilityEdgeRow> edges) {
        String canonical = canonicalText(VERSION, NODE_SET, edges);
        return new CapabilityGraph(VERSION, NODE_SET, sha256(canonical), edges.size(), edges);
    }

    private static List<NodeRow> nodes(String... codes) {
        List<NodeRow> rows = new ArrayList<>();
        for (int index = 0; index < codes.length; index++) {
            rows.add(node(index + 1, codes[index], NODE_SET));
        }
        return List.copyOf(rows);
    }

    private static NodeRow node(long id, String code, String nodeSetVersion) {
        return new NodeRow(
                id, code, 1, code, "TRL", 3, "OFFICIAL", "ACTIVE",
                null, LocalDate.of(2000, 1, 1), 1.0, false,
                "test node " + code, nodeSetVersion);
    }

    private static String canonicalText(
            String version, String nodeSetVersion, List<CapabilityEdgeRow> edges) {
        String edgeText = edges.stream()
                .sorted(Comparator.comparing(CapabilityEdgeRow::toCode)
                        .thenComparingInt(CapabilityEdgeRow::orGroup)
                        .thenComparing(CapabilityEdgeRow::fromCode))
                .map(edge -> String.format(Locale.ROOT, "%s|%02d|%s|%.3f",
                        edge.toCode(), edge.orGroup(), edge.fromCode(), edge.deltaE()))
                .collect(Collectors.joining("\n"));
        return version + "\n" + nodeSetVersion
                + (edgeText.isEmpty() ? "" : "\n" + edgeText);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
