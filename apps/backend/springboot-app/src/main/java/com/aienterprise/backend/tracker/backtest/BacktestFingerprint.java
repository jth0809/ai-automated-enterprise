package com.aienterprise.backend.tracker.backtest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class BacktestFingerprint {

    public static final String INPUT_VERSION = "tracker-backtest-input-v2";

    private BacktestFingerprint() {
    }

    public static Value of(Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return digest(canonicalBytes(descriptor));
    }

    public static long foldSeed(
            Value input,
            BacktestCandidate candidate,
            BacktestSchedule.Fold fold) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(fold, "fold");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                write(out, "tracker-backtest-fold-seed-v1");
                write(out, input.sha256());
                writeCandidate(out, candidate);
                writeFold(out, fold);
            }
            return digest(bytes.toByteArray()).seed();
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot canonicalize fold seed", impossible);
        }
    }

    private static byte[] canonicalBytes(Descriptor descriptor) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                write(out, INPUT_VERSION);
                write(out, descriptor.datasetSha256());
                write(out, descriptor.nodeSetVersion());
                write(out, descriptor.rubricVersion());
                write(out, descriptor.paramsVersion());
                write(out, descriptor.graphVersion());
                write(out, descriptor.graphSha256());
                write(out, BacktestCandidate.REGISTRY_VERSION);
                out.writeInt(descriptor.sampleCount());
                out.writeInt(BacktestSchedule.HORIZON_WEEKS);
                var candidates = BacktestCandidate.registry();
                out.writeInt(candidates.size());
                for (BacktestCandidate candidate : candidates) {
                    writeCandidate(out, candidate);
                }
                var folds = descriptor.schedule().all();
                out.writeInt(folds.size());
                for (BacktestSchedule.Fold fold : folds) {
                    writeFold(out, fold);
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot canonicalize backtest input", impossible);
        }
    }

    private static void writeCandidate(
            DataOutputStream out, BacktestCandidate candidate) throws IOException {
        out.writeInt(candidate.windowM());
        out.writeDouble(candidate.kShrink());
        out.writeDouble(candidate.deltaScale());
    }

    private static void writeFold(
            DataOutputStream out, BacktestSchedule.Fold fold) throws IOException {
        out.writeInt(fold.index());
        write(out, fold.cohort().name());
        write(out, fold.cutoff().toString());
        write(out, fold.target().toString());
    }

    private static Value digest(byte[] canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return new Value(
                    HexFormat.of().formatHex(digest),
                    ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String token(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String sha256(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        }
        return value;
    }

    public record Descriptor(
            String datasetSha256,
            String nodeSetVersion,
            String rubricVersion,
            String paramsVersion,
            String graphVersion,
            String graphSha256,
            int sampleCount,
            BacktestSchedule.Split schedule) {

        public Descriptor {
            datasetSha256 = sha256(datasetSha256, "datasetSha256");
            nodeSetVersion = token(nodeSetVersion, "nodeSetVersion");
            rubricVersion = token(rubricVersion, "rubricVersion");
            paramsVersion = token(paramsVersion, "paramsVersion");
            graphVersion = token(graphVersion, "graphVersion");
            graphSha256 = sha256(graphSha256, "graphSha256");
            if (sampleCount < 1_000 || sampleCount > 10_000) {
                throw new IllegalArgumentException("sampleCount must be 1000..10000");
            }
            schedule = Objects.requireNonNull(schedule, "schedule");
        }
    }

    public record Value(String sha256, long seed) {
        public Value {
            sha256 = BacktestFingerprint.sha256(sha256, "sha256");
            if (seed < 0) {
                throw new IllegalArgumentException("seed must be nonnegative");
            }
        }
    }
}
