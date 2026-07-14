package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.aienterprise.backend.tracker.backfill.BackfillAuditValidator;
import com.aienterprise.backend.tracker.backfill.BackfillDatasetValidator;
import com.aienterprise.backend.tracker.backfill.ValidatedBackfill;
import com.aienterprise.backend.tracker.backfill.ValidatedNodeAudit;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.math.Params;
import com.aienterprise.backend.tracker.math.Readiness;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tracker.enabled=true",
                "tracker.backfill-on-boot=false",
                "tracker.backfill-resource=tracker/backfill-sample.json",
                "tracker.backfill-candidates-resource=tracker/backfill/historical-candidates-import.jsonl",
                "tracker.backfill-dataset-version=backfill-test-v1"})
@ActiveProfiles("test")
class BackfillLoaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private BackfillLoader loader;

    @Test
    void productionImportEntryPointUsesTheSharedClusterLock() throws NoSuchMethodException {
        SchedulerLock lock = BackfillLoader.class
                .getMethod("loadDatasetIfNeeded")
                .getAnnotation(SchedulerLock.class);

        assertEquals("tracker-backfill-import", lock.name());
    }

    // The boot loader commits its transaction, so restore the shared H2
    // database for integration suites that reuse the named in-memory URL.
    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM node_state_history").update();
        jdbc.sql("DELETE FROM historical_evidence").update();
        jdbc.sql("DELETE FROM backfill_import").update();
        jdbc.sql("DELETE FROM pillar_snapshot").update();
        jdbc.sql("DELETE FROM event").update();
        jdbc.sql("""
                UPDATE capability_node
                   SET current_level = 0, verification_level = NULL,
                       node_status = 'ACTIVE', dormant_since = NULL,
                       program_end_date = NULL
                """).update();
    }

    @Test
    void importsApprovedReferencesAndRebuildsLevelsAndSnapshots() {
        loader.loadDatasetIfNeeded();

        assertEquals(9, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
        assertEquals(6, repository.findNodeByCode("P4-ISRU-PROP").currentLevel());
        assertEquals(4, repository.findNodeByCode("P6-GOV-FRAMEWORK").currentLevel());
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM event WHERE event_status = 'CONFIRMED'")
                .query(Integer.class).single());
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM event
                 WHERE event_type IN
                       ('SETBACK','PROGRAM_CANCELLATION','ANNOUNCEMENT_ONLY','RETROSPECTIVE')
                   AND state_advanced = 'Y'
                """).query(Integer.class).single());
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single());

        Integer auditCount = jdbc.sql("""
                SELECT record_count FROM backfill_import
                 WHERE dataset_version = 'backfill-test-v1'
                """).query(Integer.class).single();
        assertEquals(8, auditCount);

        int expectedYears = LocalDate.now(ZoneOffset.UTC).getYear() - 1960;
        assertEquals(expectedYears, jdbc.sql(
                "SELECT COUNT(*) FROM pillar_snapshot WHERE pillar = 1")
                .query(Integer.class).single());
        List<Double> pillarOne = jdbc.sql(
                "SELECT readiness FROM pillar_snapshot WHERE pillar = 1 ORDER BY snapshot_date")
                .query(Double.class).list();
        for (int i = 1; i < pillarOne.size(); i++) {
            assertTrue(pillarOne.get(i) >= pillarOne.get(i - 1),
                    "pillar 1 readiness regressed at index " + i);
        }
    }

    @Test
    void productionAuditReplaysApprovedLevelsDormancyAndReadiness() {
        BackfillLoader production = loader(
                new ClassPathResource("tracker/historical-candidates-v1.jsonl"),
                new ClassPathResource("tracker/backfill-v1.json"),
                "backfill-v1");

        production.loadDatasetIfNeeded();

        assertNodeState("P1-REUSE-LV", 5, "ACTIVE");
        assertNodeState("P1-ORBIT-REFUEL", 8, "ACTIVE");
        assertNodeState("P1-DEEP-PROP", 5, "DORMANT");
        assertNodeState("P1-EDL-HEAVY", 5, "ACTIVE");
        assertNodeState("P1-SURFACE-ASCENT", 8, "DORMANT");
        assertNodeState("P1-CREW-SAFE", 8, "ACTIVE");
        assertNodeState("P1-ORBIT-LOGISTICS", 8, "ACTIVE");
        assertNodeState("P1-TRANSPORT-INTEGRATION", 3, "ACTIVE");
        assertNodeState("P2-HEALTH-AUTONOMY", 3, "ACTIVE");
        assertNodeState("P3-THERMAL", 3, "ACTIVE");
        assertNodeState("P4-RESOURCE-INTEGRATION", 0, "ACTIVE");
        assertNodeState("P6-FUNDING", 3, "ACTIVE");
        assertNodeState("P6-SETTLEMENT-INTEGRATION", 0, "ACTIVE");
        assertEquals(null, repository.findNodeByCode("P6-FUNDING").programEndDate());

        ValidatedBackfill validatedBackfill = new BackfillDatasetValidator(true).validate(
                new ClassPathResource("tracker/historical-candidates-v1.jsonl"),
                new ClassPathResource("tracker/backfill-v1.json"));
        ValidatedNodeAudit audit = new BackfillAuditValidator(Clock.fixed(
                Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)).validate(
                        new ClassPathResource("tracker/backfill-audit-v1.json"),
                        validatedBackfill);
        assertTrue(audit.errors().isEmpty(), () -> String.join("\n", audit.errors()));
        assertEquals(35, audit.entries().size());
        audit.entries().forEach(entry -> {
            var actual = repository.findNodeByCode(entry.nodeCode());
            assertEquals(entry.auditedLevel(), actual.currentLevel(), entry.nodeCode());
            assertEquals(entry.status(), actual.nodeStatus(), entry.nodeCode());
        });

        var surfaceAscent = repository.findNodeByCode("P1-SURFACE-ASCENT");
        assertEquals(LocalDate.of(1972, 12, 19), surfaceAscent.programEndDate());
        assertEquals(LocalDate.of(1987, 12, 19), surfaceAscent.dormantSince());

        var p1Nodes = repository.findAllNodes().stream()
                .filter(node -> node.pillar() == 1)
                .toList();
        assertEquals(0.4624,
                Readiness.pillarReadiness(p1Nodes, Params.defaults()), 1e-9);
        int importedEvidence = jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single();
        int recordedClaims = jdbc.sql("""
                SELECT record_count FROM backfill_import
                 WHERE dataset_version = 'backfill-v1'
                """).query(Integer.class).single();
        assertEquals(recordedClaims, importedEvidence);
        assertTrue(recordedClaims > 0);

        production.loadDatasetIfNeeded();

        assertEquals(importedEvidence, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM backfill_import
                 WHERE dataset_version = 'backfill-v1'
                """).query(Integer.class).single());
    }

    @Test
    void importsIntoDatabaseThatAlreadyContainsLiveEvents() {
        jdbc.sql("""
                INSERT INTO event
                  (natural_key, node_id, event_type, claimed_level, actor, occurred_on,
                   verification_level, event_status, rubric_version_id)
                VALUES
                  ('live-existing-event',
                   (SELECT id FROM capability_node WHERE code = 'P3-COMMS'),
                   'FLIGHT_TEST', 3, 'Live source', DATE '2026-06-01',
                   'OFFICIAL', 'CONFIRMED',
                   (SELECT id FROM rubric_version WHERE version_label = 'r2.0'))
                """).update();

        loader.loadDatasetIfNeeded();

        assertEquals(9, jdbc.sql("SELECT COUNT(*) FROM event").query(Integer.class).single());
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM backfill_import")
                .query(Integer.class).single());
    }

    @Test
    void historicalReplayDoesNotOverwriteExistingLiveStateOnTheSameNode() {
        jdbc.sql("""
                UPDATE capability_node
                   SET current_level = 8, verification_level = 'OFFICIAL'
                 WHERE code = 'P1-REUSE-LV'
                """).update();
        jdbc.sql("""
                INSERT INTO event
                  (natural_key, node_id, event_type, claimed_level, actor, occurred_on,
                   verification_level, event_status, state_advanced, rubric_version_id)
                VALUES
                  ('live-existing-reuse-state',
                   (SELECT id FROM capability_node WHERE code = 'P1-REUSE-LV'),
                   'OPERATIONAL_DEPLOYMENT', 8, 'Live source', DATE '2026-06-01',
                   'OFFICIAL', 'CONFIRMED', 'Y',
                   (SELECT id FROM rubric_version WHERE version_label = 'r2.0'))
                """).update();

        loader.loadDatasetIfNeeded();

        var node = repository.findNodeByCode("P1-REUSE-LV");
        assertEquals(8, node.currentLevel());
        assertEquals("ACTIVE", node.nodeStatus());
        assertEquals(null, node.programEndDate());
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM event
                 WHERE natural_key = 'live-existing-reuse-state'
                   AND state_advanced = 'Y'
                """).query(Integer.class).single());
    }

    @Test
    void secondImportWithSameDatasetHashIsNoOp() {
        loader.loadDatasetIfNeeded();
        int snapshots = jdbc.sql("SELECT COUNT(*) FROM pillar_snapshot")
                .query(Integer.class).single();

        loader.loadDatasetIfNeeded();

        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM event").query(Integer.class).single());
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM backfill_import")
                .query(Integer.class).single());
        assertEquals(snapshots, jdbc.sql("SELECT COUNT(*) FROM pillar_snapshot")
                .query(Integer.class).single());
    }

    @Test
    void canonicalJsonAndLfNormalizationProduceSameDatasetHash() throws IOException {
        loader.loadDatasetIfNeeded();
        String candidates = candidatesText().replace("\n", "\r\n");
        String compactMappings = JSON.writeValueAsString(JSON.readTree(mappingsText()));
        BackfillLoader reformatted = loader(
                resource(candidates), resource(compactMappings), "backfill-test-v1");

        reformatted.loadDatasetIfNeeded();

        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM backfill_import")
                .query(Integer.class).single());
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single());
    }

    @Test
    void changedContentUnderSameDatasetVersionIsRejected() throws IOException {
        loader.loadDatasetIfNeeded();
        String changed = mappingsText().replaceFirst(
                "Synthetic test fixture boundary\\.", "Changed but valid boundary.");
        BackfillLoader changedLoader = loader(
                resource(candidatesText()), resource(changed), "backfill-test-v1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, changedLoader::loadDatasetIfNeeded);

        assertTrue(error.getMessage().contains("dataset hash mismatch"));
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM backfill_import")
                .query(Integer.class).single());
    }

    @Test
    void unknownSourceInMixedDatasetWritesNothing() throws IOException {
        String candidates = candidatesText().replaceFirst(
                "\"sourceCode\":\"NASA\"", "\"sourceCode\":\"MISSING\"");
        String mappings = mappingsText().replaceFirst("#NASA", "#MISSING");
        BackfillLoader invalid = loader(
                resource(candidates), resource(mappings), "backfill-invalid-source");

        assertThrows(IllegalStateException.class, invalid::loadDatasetIfNeeded);

        assertDatabaseEmpty();
    }

    @Test
    void verificationMismatchInMixedDatasetWritesNothing() throws IOException {
        String mappings = mappingsText().replaceFirst(
                "\"expectedVerificationLevel\": \"OFFICIAL\"",
                "\"expectedVerificationLevel\": \"INDEPENDENT\"");
        BackfillLoader invalid = loader(
                resource(candidatesText()), resource(mappings), "backfill-invalid-verification");

        assertThrows(IllegalStateException.class, invalid::loadDatasetIfNeeded);

        assertDatabaseEmpty();
    }

    @Test
    void replayAppliesRollbackCancellationDormancyAndLaterRestoration() {
        BackfillLoader transitions = transitionLoader();

        transitions.loadDatasetIfNeeded();

        var node = repository.findNodeByCode("P6-FUNDING");
        assertEquals(5, node.currentLevel());
        assertEquals("ACTIVE", node.nodeStatus());
        assertEquals(null, node.programEndDate());

        List<Integer> levels = jdbc.sql("""
                SELECT h.new_level
                  FROM node_state_history h
                  JOIN capability_node n ON n.id = h.node_id
                 WHERE n.code = 'P6-FUNDING'
                 ORDER BY h.id
                """).query(Integer.class).list();
        assertEquals(List.of(6, 4, 5, 5), levels);
        assertEquals("N", jdbc.sql("""
                SELECT state_advanced FROM event
                 WHERE event_type = 'PROGRAM_CANCELLATION'
                """).query(String.class).single());

        double y2000 = snapshot(6, 2000);
        double y2001 = snapshot(6, 2001);
        double y2002 = snapshot(6, 2002);
        double y2003 = snapshot(6, 2003);
        double y2019 = snapshot(6, 2019);
        double y2020 = snapshot(6, 2020);
        assertTrue(y2001 < y2000, "official rollback must lower the historical state");
        assertTrue(y2002 > y2001, "later evidence must restore the historical state");
        assertTrue(y2019 < y2003, "old cancellation must apply dormancy attenuation");
        assertTrue(y2020 > y2019, "later restoration must clear dormancy attenuation");
    }

    private void assertDatabaseEmpty() {
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM event").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
                .query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM backfill_import")
                .query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM pillar_snapshot")
                .query(Integer.class).single());
    }

    private void assertNodeState(String code, int level, String status) {
        var node = repository.findNodeByCode(code);
        assertEquals(level, node.currentLevel(), code);
        assertEquals(status, node.nodeStatus(), code);
    }

    private BackfillLoader loader(Resource candidates, Resource mappings, String version) {
        return new BackfillLoader(repository, candidates, mappings, version);
    }

    private BackfillLoader transitionLoader() {
        ObjectNode[] candidates = new ObjectNode[5];
        ArrayNode mappings = JSON.createArrayNode();
        String[] eventTypes = {
                "INSTITUTIONAL_ADVANCE", "ROLLBACK", "INSTITUTIONAL_ADVANCE",
                "PROGRAM_CANCELLATION", "INSTITUTIONAL_ADVANCE"};
        Integer[] levels = {6, 4, 5, null, 5};
        String[] dates = {
                "2000-01-01", "2001-01-01", "2002-01-01", "2003-01-01", "2020-01-01"};
        for (int i = 0; i < candidates.length; i++) {
            String candidateId = "HC-TRANSITION-" + (i + 1);
            ObjectNode candidate = JSON.createObjectNode();
            candidate.put("candidateId", candidateId);
            candidate.put("eventTitle", "Transition fixture " + (i + 1));
            candidate.putArray("candidateTopics").add("chronological replay");
            candidate.put("actor", "Transition actor " + (i + 1));
            candidate.put("occurredOn", dates[i]);
            candidate.put("occurredOnPrecision", "DAY");
            ObjectNode evidence = candidate.putArray("evidence").addObject();
            evidence.put("sourceCode", "NASA");
            evidence.put("url", "https://www.nasa.gov/transition-" + (i + 1));
            evidence.put("locator", "transition section " + (i + 1));
            evidence.put("accessedOn", "2026-07-13");
            evidence.put("contentSha256", Integer.toHexString(i + 9).repeat(64));
            evidence.put("publicationPath", "PRIMARY");
            evidence.put("factSummary", "Reviewer-authored transition fact " + (i + 1) + ".");
            candidate.put("discoveryStatus", "READY_FOR_MAPPING");
            candidate.put("discoveryNote", "Synthetic chronological boundary.");
            candidates[i] = candidate;

            ObjectNode mapping = mappings.addObject();
            mapping.put("backfillId", "BF-TRANSITION-" + (i + 1));
            mapping.put("candidateId", candidateId);
            mapping.put("nodeSetVersion", "nodes-v1.0");
            mapping.put("rubricVersion", "r2.0");
            mapping.put("nodeCode", "P6-FUNDING");
            mapping.put("eventType", eventTypes[i]);
            if (levels[i] == null) {
                mapping.putNull("claimedLevel");
            } else {
                mapping.put("claimedLevel", levels[i]);
            }
            mapping.put("actor", "Transition actor " + (i + 1));
            mapping.put("occurredOn", dates[i]);
            mapping.put("occurredOnPrecision", "DAY");
            mapping.put("expectedVerificationLevel", "OFFICIAL");
            mapping.put("eventTitle", "Transition fixture " + (i + 1));
            mapping.put("rubricJustification", "Synthetic chronological boundary.");
            if ("PROGRAM_CANCELLATION".equals(eventTypes[i])) {
                mapping.put("programEndEffect", "CAPABILITY_PROGRAM_END");
                mapping.put("programEndScope",
                        "Synthetic fixture closes the representative program lineage.");
            }
            mapping.putArray("evidenceRefs").add(candidateId + "#NASA");
            ObjectNode review = mapping.putObject("review");
            review.put("fact", "APPROVED");
            review.put("rubric", "APPROVED");
            review.put("reviewerNote", "Synthetic transition review.");
        }

        StringBuilder jsonl = new StringBuilder();
        for (ObjectNode candidate : candidates) {
            jsonl.append(candidate).append('\n');
        }
        return loader(resource(jsonl.toString()), resource(mappings.toString()), "backfill-transitions-v1");
    }

    private double snapshot(int pillar, int year) {
        return jdbc.sql("""
                SELECT readiness FROM pillar_snapshot
                 WHERE pillar = :pillar AND snapshot_date = :snapshotDate
                """)
                .param("pillar", pillar)
                .param("snapshotDate", java.sql.Date.valueOf(LocalDate.of(year, 12, 31)))
                .query(Double.class)
                .single();
    }

    private static Resource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String candidatesText() throws IOException {
        return new ClassPathResource("tracker/backfill/historical-candidates-import.jsonl")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String mappingsText() throws IOException {
        return new ClassPathResource("tracker/backfill-sample.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
