package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ingest.BackfillLoader;
import com.aienterprise.backend.tracker.ops.StateFreezeService;
import com.aienterprise.backend.tracker.ops.StateFreezeService.Trigger;

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

    @Test
    void weeklySnapshotProducesAllPillarsWithOrderedEtaInterval() {
        loader.loadIfEmpty();

        job.snapshotNow();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertEquals(7, jdbc.sql("SELECT COUNT(*) FROM pillar_snapshot WHERE snapshot_date = :today")
                .param("today", java.sql.Date.valueOf(today)).query(Integer.class).single());

        SnapshotRow pillarOne = repository.findLatestSnapshot(1).orElseThrow();
        assertNotNull(pillarOne.etaYear(), "pillar 1 has a rising backfill series, eta must resolve");
        assertNotNull(pillarOne.etaLow());
        assertNotNull(pillarOne.etaHigh());
        assertTrue(pillarOne.etaLow() <= pillarOne.etaYear());
        assertTrue(pillarOne.etaYear() <= pillarOne.etaHigh());

        // Overall row exists; its readiness is the minimum across pillars
        // (pillar 3 has no backfill events, so the overall readiness is 0).
        SnapshotRow overall = repository.findLatestSnapshot(0).orElseThrow();
        assertEquals(0.0, overall.readiness(), 1e-9);
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
}
