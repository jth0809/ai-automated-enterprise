package com.aienterprise.backend.tracker.prediction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HexFormat;

/** Canonical issuance and per-statement identities for idempotent publication. */
public final class PredictionFingerprint {

    private static final String VERSION = "prediction-fingerprint-v1";

    private PredictionFingerprint() {
    }

    public static Value of(
            PredictionInputFactory.Input input,
            PredictionCandidateSelector.Calibration calibration,
            List<PredictionCandidateSelector.Candidate> candidates) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(calibration, "calibration");
        List<PredictionCandidateSelector.Candidate> immutable = List.copyOf(
                Objects.requireNonNull(candidates, "candidates"));
        if (immutable.isEmpty()) {
            throw new IllegalArgumentException("prediction candidates are required");
        }

        StringBuilder canonical = new StringBuilder()
                .append(VERSION).append('\n')
                .append("dataset=").append(input.datasetSha256()).append('\n')
                .append("nodes=").append(input.nodeSetVersion()).append('\n')
                .append("rubric=").append(input.rubricVersion()).append('\n')
                .append("hazard=").append(input.hazardParameters().version()).append('\n')
                .append("calibration=").append(calibration.version()).append('\n')
                .append("asOf=").append(input.asOf()).append('\n')
                .append("issuedOn=").append(input.issuedOn()).append('\n');
        List<String> candidateHashes = new ArrayList<>();
        List<String> statementHashes = new ArrayList<>();
        for (PredictionCandidateSelector.Candidate candidate : immutable) {
            String candidateCanonical = canonicalCandidate(candidate);
            String candidateHash = sha256(candidateCanonical);
            candidateHashes.add(candidateHash);
            statementHashes.add(sha256(candidate.statement()));
            canonical.append("candidate=").append(candidateHash).append('\n');
        }
        String inputHash = sha256(canonical.toString());
        return new Value(
                inputHash,
                "micro-v1-" + input.issuedOn() + "-" + inputHash.substring(0, 12),
                candidateHashes, statementHashes);
    }

    static String canonicalCandidate(
            PredictionCandidateSelector.Candidate value) {
        return String.join("|",
                value.nodeCode(),
                Integer.toString(value.pillar()),
                Boolean.toString(value.integrationNode()),
                Double.toHexString(value.nodeWeight()),
                Integer.toString(value.currentLevel()),
                Integer.toString(value.targetLevel()),
                value.issuedOn().toString(),
                value.dueOn().toString(),
                Integer.toString(value.horizonMonths()),
                Double.toHexString(value.rawProbability()),
                Double.toHexString(value.calibratedProbability()),
                Double.toHexString(value.issuedProbability()),
                value.informationStatus().name(),
                value.calibrationVersion(),
                value.statement(),
                Integer.toString(value.advanceCount()),
                Double.toHexString(value.exposureYears()),
                Double.toHexString(value.pillarRate()),
                Double.toHexString(value.nodeRate()));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record Value(
            String inputSha256,
            String cohortKey,
            List<String> candidateSha256,
            List<String> statementSha256) {

        public Value {
            if (!sha(inputSha256) || cohortKey == null || cohortKey.isBlank()) {
                throw new IllegalArgumentException("invalid prediction fingerprint");
            }
            candidateSha256 = List.copyOf(candidateSha256);
            statementSha256 = List.copyOf(statementSha256);
            if (candidateSha256.isEmpty()
                    || candidateSha256.size() != statementSha256.size()
                    || candidateSha256.stream().anyMatch(value -> !sha(value))
                    || statementSha256.stream().anyMatch(value -> !sha(value))) {
                throw new IllegalArgumentException("invalid candidate fingerprints");
            }
        }

        private static boolean sha(String value) {
            return value != null && value.matches("[0-9a-f]{64}");
        }
    }
}
