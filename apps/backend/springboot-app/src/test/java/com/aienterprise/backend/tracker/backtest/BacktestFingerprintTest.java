package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class BacktestFingerprintTest {

    @Test
    void identicalDescriptorHasStableHashAndSeed() {
        BacktestFingerprint.Descriptor first = descriptor(1_000, "b".repeat(64));
        BacktestFingerprint.Descriptor second = descriptor(1_000, "b".repeat(64));

        BacktestFingerprint.Value one = BacktestFingerprint.of(first);
        BacktestFingerprint.Value two = BacktestFingerprint.of(second);

        assertEquals(one, two);
        assertTrue(one.sha256().matches("[0-9a-f]{64}"));
        assertTrue(one.seed() >= 0);
    }

    @Test
    void datasetGraphSamplesAndScheduleAffectTheHash() {
        BacktestFingerprint.Value baseline = BacktestFingerprint.of(
                descriptor(1_000, "b".repeat(64)));

        assertNotEquals(baseline, BacktestFingerprint.of(
                descriptor(2_000, "b".repeat(64))));
        assertNotEquals(baseline, BacktestFingerprint.of(
                descriptor(1_000, "c".repeat(64))));
        BacktestFingerprint.Descriptor earlier = new BacktestFingerprint.Descriptor(
                "a".repeat(64), "nodes-v1.0", "r2.0", "params-v2",
                "graph-v1.0", "b".repeat(64), 1_000,
                BacktestSchedule.create(LocalDate.of(2025, 12, 29)));
        assertNotEquals(baseline, BacktestFingerprint.of(earlier));
    }

    @Test
    void foldSeedChangesByCandidateAndCutoffButIsRepeatable() {
        BacktestFingerprint.Value value = BacktestFingerprint.of(
                descriptor(1_000, "b".repeat(64)));
        BacktestSchedule.Fold fold = BacktestSchedule.create(
                LocalDate.of(2026, 7, 13)).calibration().folds().getFirst();

        long first = BacktestFingerprint.foldSeed(
                value, BacktestCandidate.DEFAULT, fold);
        assertEquals(first, BacktestFingerprint.foldSeed(
                value, BacktestCandidate.DEFAULT, fold));
        assertNotEquals(first, BacktestFingerprint.foldSeed(
                value, new BacktestCandidate(4, 4, 1.0), fold));
    }

    private static BacktestFingerprint.Descriptor descriptor(
            int samples, String graphHash) {
        return new BacktestFingerprint.Descriptor(
                "a".repeat(64), "nodes-v1.0", "r2.0", "params-v2",
                "graph-v1.0", graphHash, samples,
                BacktestSchedule.create(LocalDate.of(2026, 7, 13)));
    }
}
