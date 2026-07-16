package com.aienterprise.backend.tracker.projection;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityEdgeRow;
import com.aienterprise.backend.tracker.graph.NodeReadinessResult;
import com.aienterprise.backend.tracker.math.ParameterUncertainty;
import com.aienterprise.backend.tracker.math.Params;
import com.aienterprise.backend.tracker.math.PillarTrendResult;

public final class ProjectionFingerprint {

    private ProjectionFingerprint() {
    }

    public static Value of(ProjectionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("projection input is required");
        }
        byte[] canonical = canonicalBytes(input);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            long seed = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
            return new Value(HexFormat.of().formatHex(digest), seed);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] canonicalBytes(ProjectionInput input) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writeString(out, "tracker-projection-input-v1");
                writeString(out, input.asOf().toString());
                writeString(out, input.datasetSha256());
                writeString(out, input.nodeSetVersion());
                out.writeInt(input.sampleCount());
                out.writeDouble(input.targetReadiness());

                writeNodes(out, input);
                writeGraph(out, input);
                writeModel(out, input);
                writeReadiness(out, input);
                writeTrends(out, input);
                for (int pillar = 0; pillar <= 6; pillar++) {
                    out.writeInt(pillar);
                    writeString(out, input.momentum().get(pillar).name());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot canonicalize projection input", impossible);
        }
    }

    private static void writeNodes(
            DataOutputStream out, ProjectionInput input) throws IOException {
        var nodes = input.nodes().stream()
                .sorted(Comparator.comparing(NodeRow::code)
                        .thenComparingLong(NodeRow::id))
                .toList();
        out.writeInt(nodes.size());
        for (NodeRow node : nodes) {
            out.writeLong(node.id());
            writeString(out, node.code());
            out.writeInt(node.pillar());
            writeString(out, node.nameKo());
            writeString(out, node.scaleType());
            out.writeInt(node.currentLevel());
            writeString(out, node.verificationLevel());
            writeString(out, node.nodeStatus());
            writeNullableString(out, node.dormantSince() == null
                    ? null : node.dormantSince().toString());
            writeNullableString(out, node.programEndDate() == null
                    ? null : node.programEndDate().toString());
            out.writeDouble(node.weight());
            out.writeBoolean(node.integrationNode());
            writeString(out, node.description());
            writeString(out, node.nodeSetVersion());
        }
    }

    private static void writeGraph(
            DataOutputStream out, ProjectionInput input) throws IOException {
        var graph = input.graph();
        writeString(out, graph.version());
        writeString(out, graph.nodeSetVersion());
        writeString(out, graph.declaredSha256());
        out.writeInt(graph.declaredEdgeCount());
        var edges = graph.edges().stream()
                .sorted(Comparator.comparing(CapabilityEdgeRow::toCode)
                        .thenComparingInt(CapabilityEdgeRow::orGroup)
                        .thenComparing(CapabilityEdgeRow::fromCode))
                .toList();
        out.writeInt(edges.size());
        for (CapabilityEdgeRow edge : edges) {
            writeString(out, edge.graphVersion());
            writeString(out, edge.fromCode());
            writeString(out, edge.toCode());
            out.writeInt(edge.orGroup());
            out.writeDouble(edge.deltaE());
        }
    }

    private static void writeModel(
            DataOutputStream out, ProjectionInput input) throws IOException {
        Params params = input.model().params();
        writeString(out, params.version());
        out.writeDouble(params.epsilon());
        out.writeDouble(params.kShrink());
        out.writeInt(params.windowM());
        out.writeInt(params.windowFixedYears());
        out.writeInt(params.windowMinYears());
        out.writeInt(params.windowMaxYears());
        out.writeDouble(params.dormancyStart());
        out.writeDouble(params.dormancyStepPerDecade());
        out.writeDouble(params.dormancyFloor());
        out.writeInt(params.dormancyTriggerYears());
        out.writeDouble(params.defaultDeltaE());
        out.writeInt(params.etaClampMinYears());
        out.writeInt(params.etaClampMaxYears());
        out.writeInt(params.displayDampingDaysPerDay());
        out.writeDouble(params.dailyCostCapUsd());
        writeLevelMap(out, params.trlMap());
        writeLevelMap(out, params.maturityMap());

        Map<String, ParameterUncertainty> uncertainty =
                new TreeMap<>(input.model().uncertainty());
        out.writeInt(uncertainty.size());
        for (var entry : uncertainty.entrySet()) {
            ParameterUncertainty value = entry.getValue();
            writeString(out, entry.getKey());
            writeString(out, value.name());
            writeString(out, value.distribution());
            out.writeDouble(value.lower());
            out.writeDouble(value.central());
            out.writeDouble(value.upper());
            out.writeBoolean(value.scale() != null);
            if (value.scale() != null) {
                out.writeDouble(value.scale());
            }
        }
    }

    private static void writeReadiness(
            DataOutputStream out, ProjectionInput input) throws IOException {
        var readiness = input.centralReadiness();
        writeString(out, readiness.graphVersion());
        var nodes = new TreeMap<>(readiness.nodes());
        out.writeInt(nodes.size());
        for (var entry : nodes.entrySet()) {
            NodeReadinessResult value = entry.getValue();
            writeString(out, entry.getKey());
            writeString(out, value.nodeCode());
            out.writeDouble(value.rawReadiness());
            out.writeDouble(value.effectiveReadiness());
            writeNullableDouble(out, value.dependencyCap());
            out.writeInt(value.limitingGroups().size());
            value.limitingGroups().stream().sorted().forEach(group -> {
                try {
                    out.writeInt(group);
                } catch (IOException impossible) {
                    throw new CanonicalWriteFailure(impossible);
                }
            });
            var dependencies = value.limitingDependencies().stream().sorted().toList();
            out.writeInt(dependencies.size());
            for (String dependency : dependencies) {
                writeString(out, dependency);
            }
        }
        writePillarMap(out, readiness.rawPillarReadiness());
        writePillarMap(out, readiness.effectivePillarReadiness());
    }

    private static void writeTrends(
            DataOutputStream out, ProjectionInput input) throws IOException {
        out.writeDouble(input.trends().priorSlope());
        for (int pillar = 1; pillar <= 6; pillar++) {
            PillarTrendResult value = input.trends().pillars().get(pillar);
            out.writeInt(value.pillar());
            out.writeDouble(value.readiness());
            writeNullableDouble(out, value.trendFit());
            out.writeDouble(value.trendPrior());
            out.writeDouble(value.trendUsed());
            out.writeInt(value.eventsInWindow());
            out.writeInt(value.windowYears());
            writeNullableDouble(out, value.medianIntervalYears());
            out.writeBoolean(value.regimeBreakId() != null);
            if (value.regimeBreakId() != null) {
                out.writeLong(value.regimeBreakId());
            }
            writeNullableString(out, value.regimeStart() == null
                    ? null : value.regimeStart().toString());
            writeNullableDouble(out, value.etaYear());
            writeNullableDouble(out, value.etaLow());
            writeNullableDouble(out, value.etaHigh());
            out.writeDouble(value.residualSe());
            out.writeDouble(value.slopeStandardError());
            var shifts = new TreeMap<>(value.levelShifts());
            out.writeInt(shifts.size());
            for (var shift : shifts.entrySet()) {
                writeString(out, shift.getKey().toString());
                out.writeDouble(shift.getValue());
            }
            out.writeInt(value.observations());
        }
    }

    private static void writeLevelMap(
            DataOutputStream out, Map<Integer, Double> values) throws IOException {
        for (int level = 1; level <= 9; level++) {
            out.writeInt(level);
            out.writeDouble(values.get(level));
        }
    }

    private static void writePillarMap(
            DataOutputStream out, Map<Integer, Double> values) throws IOException {
        for (int pillar = 1; pillar <= 6; pillar++) {
            out.writeInt(pillar);
            out.writeDouble(values.get(pillar));
        }
    }

    private static void writeString(
            DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeNullableString(
            DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(out, value);
        }
    }

    private static void writeNullableDouble(
            DataOutputStream out, Double value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeDouble(value);
        }
    }

    public record Value(String sha256, long seed) {
        public Value {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || seed < 0) {
                throw new IllegalArgumentException("invalid projection fingerprint");
            }
        }
    }

    private static final class CanonicalWriteFailure extends RuntimeException {
        private CanonicalWriteFailure(IOException cause) {
            super(cause);
        }
    }
}
