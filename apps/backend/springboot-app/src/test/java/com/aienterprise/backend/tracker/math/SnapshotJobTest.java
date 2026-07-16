package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ingest.BackfillLoader;
import com.aienterprise.backend.tracker.ops.StateFreezeService;
import com.aienterprise.backend.tracker.ops.StateFreezeService.Trigger;
import com.aienterprise.backend.tracker.projection.ProjectionService;

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
class SnapshotJobTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private BackfillLoader loader;

    @Autowired
    private SnapshotJob job;

    @Autowired
    private StateFreezeService freezeService;

    @Autowired
    private ObjectProvider<ProjectionService> projectionService;

    @Test
    void weeklySnapshotProducesAllPillarsWithOrderedEtaInterval() {
        loader.loadIfEmpty();

        job.snapshotNow();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertEquals(7, jdbc.sql("SELECT COUNT(*) FROM pillar_snapshot WHERE snapshot_date = :today")
                .param("today", java.sql.Date.valueOf(today)).query(Integer.class).single());

        SnapshotRow pillarOne = repository.findLatestSnapshot(1).orElseThrow();
        assertEquals("params-v2", pillarOne.paramsVersion());
        assertNotNull(pillarOne.trendFit());
        assertNotNull(pillarOne.trendUsed());
        assertNotNull(pillarOne.eventsInWindow());
        assertTrue(pillarOne.windowYears() >= 4 && pillarOne.windowYears() <= 15);
        assertNotNull(pillarOne.etaYear(), "pillar 1 has a rising backfill series, eta must resolve");
        assertNotNull(pillarOne.etaLow());
        assertNotNull(pillarOne.etaHigh());
        assertTrue(pillarOne.etaLow() <= pillarOne.etaYear());
        assertTrue(pillarOne.etaYear() <= pillarOne.etaHigh());

        // Overall row exists; its readiness is the minimum across pillars
        // (pillar 3 has no backfill events, so the overall readiness is 0).
        SnapshotRow overall = repository.findLatestSnapshot(0).orElseThrow();
        assertEquals(0.0, overall.readiness(), 1e-9);
        assertEquals("params-v2", overall.paramsVersion());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM projection_run")
                .query(Integer.class).single(),
                "the default-off snapshot path must not create a projection run");
        assertNull(projectionService.getIfAvailable(),
                "the projection service must be absent while its flag is off");
    }

    @Test
    void snapshotPersistsSixPillarShrinkageFromOneCutoffSafePrior() {
        loader.loadIfEmpty();

        job.snapshotNow();

        List<SnapshotRow> rows = new ArrayList<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            rows.add(repository.findLatestSnapshot(pillar).orElseThrow());
        }
        double prior = rows.stream()
                .map(SnapshotRow::trendFit)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average().orElseThrow();
        for (SnapshotRow row : rows) {
            assertEquals("params-v2", row.paramsVersion());
            assertNotNull(row.eventsInWindow());
            assertNotNull(row.windowYears());
            assertNotNull(row.trendUsed());
            double expected = row.trendFit() == null
                    ? prior
                    : CompleteTrendModel.shrink(
                            row.trendFit(), row.eventsInWindow(), prior, 4.0);
            assertEquals(expected, row.trendUsed(), 1e-8);
        }
    }

    @Test
    void approvedRegimeBreakResetsThePersistedEventWindow() {
        loader.loadIfEmpty();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate breakDate = today.minusYears(1);
        long causeEventId = jdbc.sql("SELECT MIN(id) FROM event")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO model_regime_break
                  (pillar, break_date, cause_event_id, review_status,
                   reviewer, reviewer_note, params_version)
                VALUES
                  (1, :breakDate, :causeEventId, 'APPROVED',
                   'snapshot-test', 'Approved cutoff reset.', 'params-v2')
                """)
                .param("breakDate", java.sql.Date.valueOf(breakDate))
                .param("causeEventId", causeEventId)
                .update();

        int expectedEvents = Math.toIntExact(jdbc.sql("""
                SELECT COUNT(*)
                  FROM node_state_history h
                  JOIN event e ON e.id = h.cause_event_id
                  JOIN capability_node n ON n.id = h.node_id
                 WHERE n.pillar = 1
                   AND e.event_status = 'CONFIRMED'
                   AND e.occurred_on BETWEEN :breakDate AND :today
                   AND (h.prev_level <> h.new_level OR h.prev_status <> h.new_status)
                """)
                .param("breakDate", java.sql.Date.valueOf(breakDate))
                .param("today", java.sql.Date.valueOf(today))
                .query(Long.class).single());

        job.snapshotNow();

        SnapshotRow row = repository.findLatestSnapshot(1).orElseThrow();
        assertEquals(expectedEvents, row.eventsInWindow());
        assertEquals(10, row.windowYears(),
                "fewer than three post-break changes use the fixed fallback");
    }

    @Test
    void invalidActiveParametersPreserveTheLastCompletedSnapshot() {
        loader.loadIfEmpty();
        job.snapshotNow();
        SnapshotRow before = repository.findLatestSnapshot(1).orElseThrow();

        jdbc.sql("UPDATE parameter_set SET active = 'N'").update();

        assertThrows(IllegalStateException.class, job::snapshotNow);
        assertEquals(before, repository.findLatestSnapshot(1).orElseThrow());
    }

    @Test
    void dampingLimitsTheDisplayedShiftPerElapsedDay() {
        // A 3-year jump forward may move the displayed ETA by at most 90 days
        // per elapsed day.
        double dampened = SnapshotJob.dampDisplayed(2100.0, 2097.0, 1.0);
        assertEquals(2100.0 - 90.0 / 365.25, dampened, 1e-9);

        // Shifts inside the allowance pass through unchanged.
        assertEquals(2100.1, SnapshotJob.dampDisplayed(2100.0, 2100.1, 7.0), 1e-9);
    }

    @Test
    void frozenSnapshotRetainsLastDisplayedEtaWithoutUpdatingItsOpsState() {
        repository.putOpsState("LAST_DISPLAYED_ETA", "2100.5");
        var before = repository.findOpsState("LAST_DISPLAYED_ETA").orElseThrow();
        freezeService.freeze("drift drill", Trigger.DRILL);

        job.snapshotNow();

        SnapshotRow overall = repository.findLatestSnapshot(0).orElseThrow();
        assertEquals(2100.5, overall.displayedEtaYear(), 1e-9);
        var after = repository.findOpsState("LAST_DISPLAYED_ETA").orElseThrow();
        assertEquals(before.value(), after.value());
        assertEquals(before.updatedAt(), after.updatedAt());
    }

    @Test
    void snapshotPersistsRawEffectiveAndGraphVersionWithoutMutatingNodeLevels() {
        loader.loadIfEmpty();
        jdbc.sql("""
                UPDATE capability_node
                   SET current_level = 9
                 WHERE code = 'P1-TRANSPORT-INTEGRATION'
                """).update();
        jdbc.sql("""
                UPDATE capability_node
                   SET current_level = 1
                 WHERE code = 'P1-DEEP-PROP'
                """).update();
        Map<String, Integer> before = nodeLevels();

        job.snapshotNow();

        SnapshotRow pillarOne = repository.findLatestSnapshot(1).orElseThrow();
        assertEquals("graph-v1.0", pillarOne.graphVersion());
        assertNotNull(pillarOne.rawReadiness());
        assertTrue(pillarOne.readiness() < pillarOne.rawReadiness());
        assertEquals(before, nodeLevels(), "DAG evaluation must not mutate observed levels");

        SnapshotRow overall = repository.findLatestSnapshot(0).orElseThrow();
        assertEquals("graph-v1.0", overall.graphVersion());
        assertNotNull(overall.rawReadiness());
        assertTrue(overall.readiness() <= overall.rawReadiness());
    }

    @Test
    void invalidGraphPreservesTheLastCompletedSnapshot() {
        loader.loadIfEmpty();
        job.snapshotNow();
        SnapshotRow before = repository.findLatestSnapshot(1).orElseThrow();

        jdbc.sql("""
                UPDATE capability_graph_version
                   SET edge_sha256 = :invalidHash
                 WHERE version_label = 'graph-v1.0'
                """)
                .param("invalidHash", "0".repeat(64))
                .update();

        assertThrows(IllegalStateException.class, job::snapshotNow);

        SnapshotRow after = repository.findLatestSnapshot(1).orElseThrow();
        assertEquals(before, after);
    }

    private Map<String, Integer> nodeLevels() {
        return jdbc.sql("SELECT code, current_level FROM capability_node ORDER BY code")
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("code"), rs.getInt("current_level")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
