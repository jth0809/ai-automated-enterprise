package com.aienterprise.backend.tracker.graph;

public record CapabilityEdgeRow(
        String graphVersion,
        String fromCode,
        String toCode,
        int orGroup,
        double deltaE) {
}
