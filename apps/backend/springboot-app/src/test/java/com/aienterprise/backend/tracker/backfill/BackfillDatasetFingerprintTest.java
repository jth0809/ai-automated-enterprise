package com.aienterprise.backend.tracker.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BackfillDatasetFingerprintTest {

    @Test
    void canonicalFingerprintIsStableAndVersionBound() {
        var candidates = new ClassPathResource(
                "tracker/historical-candidates-v1.jsonl");
        var mappings = new ClassPathResource("tracker/backfill-v1.json");

        String first = BackfillDatasetFingerprint.sha256(
                candidates, mappings, "backfill-v1", "nodes-v1.0", "r2.0");
        String second = BackfillDatasetFingerprint.sha256(
                candidates, mappings, "backfill-v1", "nodes-v1.0", "r2.0");
        String changedVersion = BackfillDatasetFingerprint.sha256(
                candidates, mappings, "backfill-v2", "nodes-v1.0", "r2.0");

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNotEquals(first, changedVersion);
    }
}
