package com.aienterprise.backend.tracker.projection;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.CapabilityGraphValidator;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.math.CompleteTrendModel;
import com.aienterprise.backend.tracker.math.ModelParameterValidator;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.MomentumService;

public record ProjectionInput(
        LocalDate asOf,
        String datasetSha256,
        String nodeSetVersion,
        List<NodeRow> nodes,
        CapabilityGraph graph,
        ModelParameters model,
        ReadinessResult centralReadiness,
        CompleteTrendModel.Result trends,
        Map<Integer, MomentumService.Status> momentum,
        int sampleCount,
        double targetReadiness) {

    private static final Set<Integer> PILLARS = Set.of(1, 2, 3, 4, 5, 6);
    private static final Set<Integer> RESULT_ROWS = Set.of(0, 1, 2, 3, 4, 5, 6);
    private static final double TOLERANCE = 1e-12;

    public ProjectionInput {
        asOf = Objects.requireNonNull(asOf, "asOf");
        datasetSha256 = requireSha256(datasetSha256);
        nodeSetVersion = requireText(nodeSetVersion, "nodeSetVersion");
        graph = Objects.requireNonNull(graph, "graph");
        model = Objects.requireNonNull(model, "model");
        centralReadiness = Objects.requireNonNull(
                centralReadiness, "centralReadiness");
        trends = Objects.requireNonNull(trends, "trends");

        nodes = Objects.requireNonNull(nodes, "nodes").stream()
                .sorted(Comparator.comparing(NodeRow::code)
                        .thenComparingLong(NodeRow::id))
                .toList();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("projection nodes are required");
        }
        if (!graph.nodeSetVersion().equals(nodeSetVersion)) {
            throw new IllegalArgumentException("graph/node-set version mismatch");
        }
        new CapabilityGraphValidator().validate(graph, nodes);
        new ModelParameterValidator().validate(model);

        validateReadiness(centralReadiness, graph, nodes);
        validateTrends(trends, centralReadiness);

        momentum = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(momentum, "momentum")));
        if (!momentum.keySet().equals(RESULT_ROWS)
                || momentum.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "momentum must contain exactly rows 0 through 6");
        }

        var samples = model.uncertainty().get("mc_samples");
        if (sampleCount < 1_000 || sampleCount > 10_000
                || sampleCount < samples.lower() || sampleCount > samples.upper()) {
            throw new IllegalArgumentException(
                    "sample count must be within the approved registry range");
        }
        if (!Double.isFinite(targetReadiness)
                || targetReadiness <= 0 || targetReadiness >= 1) {
            throw new IllegalArgumentException(
                    "target readiness must be strictly between zero and one");
        }
    }

    private static void validateReadiness(
            ReadinessResult readiness,
            CapabilityGraph graph,
            List<NodeRow> nodes) {
        if (!graph.version().equals(readiness.graphVersion())
                || !readiness.rawPillarReadiness().keySet().equals(PILLARS)
                || !readiness.effectivePillarReadiness().keySet().equals(PILLARS)
                || !readiness.nodes().keySet().equals(nodes.stream()
                        .map(NodeRow::code).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("central readiness is incomplete");
        }
        readiness.rawPillarReadiness().values().forEach(
                ProjectionInput::requireUnitValue);
        readiness.effectivePillarReadiness().values().forEach(
                ProjectionInput::requireUnitValue);
    }

    private static void validateTrends(
            CompleteTrendModel.Result trends,
            ReadinessResult readiness) {
        if (!trends.pillars().keySet().equals(PILLARS)) {
            throw new IllegalArgumentException("trend input must contain six pillars");
        }
        for (int pillar = 1; pillar <= 6; pillar++) {
            double expected = readiness.effectivePillarReadiness().get(pillar);
            double actual = trends.pillars().get(pillar).readiness();
            if (Math.abs(expected - actual) > TOLERANCE) {
                throw new IllegalArgumentException(
                        "trend/readiness mismatch for pillar " + pillar);
            }
        }
    }

    private static void requireUnitValue(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(
                    "readiness values must be finite and within [0,1]");
        }
    }

    private static String requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "datasetSha256 must be lowercase SHA-256");
        }
        return value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
