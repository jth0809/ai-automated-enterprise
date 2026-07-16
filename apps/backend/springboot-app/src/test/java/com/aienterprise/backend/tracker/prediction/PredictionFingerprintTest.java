package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.NodeRow;

class PredictionFingerprintTest {

    @Test
    void isStableAndSensitiveToPublishedProbabilityInputs() {
        var calibration = PredictionCandidateSelector.Calibration.identity();
        var first = List.of(candidate(0.20, 0.20));

        PredictionFingerprint.Value a = PredictionFingerprint.of(
                input(), calibration, first);
        PredictionFingerprint.Value b = PredictionFingerprint.of(
                input(), calibration, first);
        PredictionFingerprint.Value changed = PredictionFingerprint.of(
                input(), calibration, List.of(candidate(0.21, 0.21)));

        assertEquals(a, b);
        assertEquals(64, a.inputSha256().length());
        assertEquals("micro-v1-2026-07-16-" + a.inputSha256().substring(0, 12),
                a.cohortKey());
        assertNotEquals(a.inputSha256(), changed.inputSha256());
        assertNotEquals(a.candidateSha256().getFirst(),
                changed.candidateSha256().getFirst());
    }

    private static PredictionInputFactory.Input input() {
        NodeRow node = new NodeRow(
                1, "P1-A", 1, "Node A", "TRL", 3, "OFFICIAL", "ACTIVE",
                null, null, 1.0, false, "fixture", "nodes-v1.0");
        return new PredictionInputFactory.Input(
                "a".repeat(64), "nodes-v1.0", "r2.0",
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 16),
                List.of(node), List.of(), 15, HazardParameters.defaults());
    }

    private static PredictionCandidateSelector.Candidate candidate(
            double raw, double issued) {
        return new PredictionCandidateSelector.Candidate(
                1, "P1-A", "Node A", 1, false, 1.0, 3, 4,
                LocalDate.of(2026, 7, 16), LocalDate.of(2027, 7, 16),
                12, raw, issued, issued, issued * (1 - issued),
                PredictionCandidateSelector.InformationStatus.INFORMATIVE,
                "identity-v1", "Node A statement", 2, 20, 0.1, 0.2);
    }
}
