package com.aienterprise.backend.tracker.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ReadinessResult(
        String graphVersion,
        Map<String, NodeReadinessResult> nodes,
        Map<Integer, Double> rawPillarReadiness,
        Map<Integer, Double> effectivePillarReadiness) {

    public ReadinessResult {
        graphVersion = Objects.requireNonNull(graphVersion, "graphVersion");
        nodes = immutableCopy(nodes, "nodes");
        rawPillarReadiness = immutableCopy(rawPillarReadiness, "rawPillarReadiness");
        effectivePillarReadiness = immutableCopy(
                effectivePillarReadiness, "effectivePillarReadiness");
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> source, String label) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(source, label)));
    }
}
