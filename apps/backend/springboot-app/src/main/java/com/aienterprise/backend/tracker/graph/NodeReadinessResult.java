package com.aienterprise.backend.tracker.graph;

import java.util.List;
import java.util.Objects;

public record NodeReadinessResult(
        String nodeCode,
        double rawReadiness,
        double effectiveReadiness,
        Double dependencyCap,
        List<Integer> limitingGroups,
        List<String> limitingDependencies) {

    public NodeReadinessResult {
        nodeCode = Objects.requireNonNull(nodeCode, "nodeCode");
        limitingGroups = List.copyOf(Objects.requireNonNull(
                limitingGroups, "limitingGroups"));
        limitingDependencies = List.copyOf(Objects.requireNonNull(
                limitingDependencies, "limitingDependencies"));
    }
}
