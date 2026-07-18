package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.ingest.BackfillLoader;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tracker.enabled=true",
                "tracker.backfill-on-boot=false",
                "tracker.backfill-resource=tracker/backfill-sample.json",
                "tracker.backfill-candidates-resource=tracker/backfill/historical-candidates-import.jsonl",
                "tracker.backfill-dataset-version=backfill-test-v1"})
@ActiveProfiles("test")
@Transactional
class EvidenceCoverageServiceTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private BackfillLoader loader;

    @Autowired
    private EvidenceCoverageService service;

    @BeforeEach
    void clearBackfillState() {
        jdbc.sql("DELETE FROM node_state_history").update();
        jdbc.sql("DELETE FROM historical_evidence").update();
        jdbc.sql("DELETE FROM backfill_import").update();
        jdbc.sql("DELETE FROM pillar_snapshot").update();
        jdbc.sql("DELETE FROM ops_state WHERE state_key = 'BACKFILL_WEEKLY_PROJECTION_V1'")
                .update();
        jdbc.sql("DELETE FROM event").update();
        jdbc.sql("""
                UPDATE capability_node
                   SET current_level = 0, verification_level = NULL,
                       node_status = 'ACTIVE', dormant_since = NULL,
                       program_end_date = NULL
                """).update();
    }

    @Test
    void derivesCoverageFromPersistedApprovedReferences() {
        loader.loadDatasetIfNeeded();

        EvidenceCoverage result = service.current();

        assertEquals(8, result.historicalCandidateCount());
        assertEquals(8, result.approvedClaimCount());
        assertEquals(8, result.distinctCandidatesUsed());
        assertEquals(35, result.activeNodeCount());
        assertEquals(5, result.directlyMappedActiveNodeCount());
        assertEquals(8, result.singleEvidenceClaimCount());
        assertEquals(Map.of("OFFICIAL", 8), result.verificationLevelCounts());
    }

    @Test
    void returnsZeroCoverageWithoutImportedEvidence() {
        EvidenceCoverage result = service.current();

        assertEquals(0, result.historicalCandidateCount());
        assertEquals(0, result.approvedClaimCount());
        assertEquals(0, result.distinctCandidatesUsed());
        assertEquals(35, result.activeNodeCount());
        assertEquals(0, result.directlyMappedActiveNodeCount());
        assertEquals(0, result.singleEvidenceClaimCount());
        assertEquals(Map.of(), result.verificationLevelCounts());
    }
}
