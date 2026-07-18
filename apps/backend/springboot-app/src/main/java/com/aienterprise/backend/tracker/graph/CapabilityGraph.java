package com.aienterprise.backend.tracker.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public record CapabilityGraph(
        String version,
        String nodeSetVersion,
        String declaredSha256,
        int declaredEdgeCount,
        List<CapabilityEdgeRow> edges) {

    public CapabilityGraph {
        version = Objects.requireNonNull(version, "version");
        nodeSetVersion = Objects.requireNonNull(nodeSetVersion, "nodeSetVersion");
        declaredSha256 = Objects.requireNonNull(declaredSha256, "declaredSha256");
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    public String canonicalText() {
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

    public String computedSha256() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalText().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
