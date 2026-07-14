package com.aienterprise.backend.tracker.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.math.LogitEta;
import com.aienterprise.backend.tracker.math.Params;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"tracker.enabled=true", "tracker.backfill-on-boot=false"})
@ActiveProfiles("test")
class WeeklyBackfillProjectorTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final LocalDate FIRST_MONDAY = LocalDate.of(1957, 1, 7);

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM pillar_snapshot").update();
        jdbc.sql("DELETE FROM ops_state WHERE state_key = :key")
                .param("key", WeeklyBackfillProjector.MARKER_KEY)
                .update();
    }

    @Test
    void projectsContinuousMondaysFrom1957ThroughLastCompletedMonday() {
        WeeklyBackfillProjector projector = projector("2026-07-14T00:00:00Z");
        repository.replacePillarSnapshot(
                1, LocalDate.of(1960, 12, 31), 0.25,
                LogitEta.logitClipped(0.25, Params.defaults().epsilon()), "params-v1");

        projector.project(List.of(), HASH_A, "nodes-v1.0", "r2.0");

        LocalDate expectedLast = LocalDate.of(2026, 7, 13);
        long expectedWeeks = ChronoUnit.WEEKS.between(FIRST_MONDAY, expectedLast) + 1;
        List<LocalDate> dates = jdbc.sql("""
                SELECT snapshot_date FROM pillar_snapshot
                 WHERE pillar = 1 ORDER BY snapshot_date
                """).query(LocalDate.class).list();
        assertEquals(expectedWeeks, dates.size());
        assertEquals(FIRST_MONDAY, dates.getFirst());
        assertEquals(expectedLast, dates.getLast());
        assertTrue(dates.stream().allMatch(date -> date.getDayOfWeek() == DayOfWeek.MONDAY));
        assertEquals(expectedWeeks * 6, jdbc.sql("""
                SELECT COUNT(*) FROM pillar_snapshot WHERE pillar BETWEEN 1 AND 6
                """).query(Long.class).single());
        assertEquals(0.0, readiness(1, FIRST_MONDAY), 1e-12);
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM pillar_snapshot WHERE snapshot_date = DATE '1960-12-31'
                """).query(Integer.class).single());
    }

    @Test
    void replaysAdvanceRollbackProgramEndDormancyAndRestorationOnMondays() {
        WeeklyBackfillProjector projector = projector("1974-01-08T00:00:00Z");
        List<BackfillClaim> claims = List.of(
                claim("BF-WEEK-1", "INSTITUTIONAL_ADVANCE", 6,
                        LocalDate.of(1957, 1, 7), ProgramEndEffect.NONE),
                claim("BF-WEEK-2", "ROLLBACK", 4,
                        LocalDate.of(1957, 1, 14), ProgramEndEffect.NONE),
                claim("BF-WEEK-3", "INSTITUTIONAL_ADVANCE", 5,
                        LocalDate.of(1957, 1, 21), ProgramEndEffect.NONE),
                claim("BF-WEEK-4", "PROGRAM_CANCELLATION", null,
                        LocalDate.of(1958, 1, 6), ProgramEndEffect.CAPABILITY_PROGRAM_END),
                claim("BF-WEEK-5", "INSTITUTIONAL_ADVANCE", 5,
                        LocalDate.of(1974, 1, 7), ProgramEndEffect.NONE));

        projector.project(claims, HASH_A, "nodes-v1.0", "r2.0");

        assertEquals(0.17 * 0.45, readiness(6, LocalDate.of(1957, 1, 7)), 1e-9);
        assertEquals(0.17 * 0.20, readiness(6, LocalDate.of(1957, 1, 14)), 1e-9);
        assertEquals(0.17 * 0.30, readiness(6, LocalDate.of(1957, 1, 21)), 1e-9);
        assertEquals(0.17 * 0.30 * 0.85,
                readiness(6, LocalDate.of(1973, 1, 8)), 1e-9);
        assertEquals(0.17 * 0.30, readiness(6, LocalDate.of(1974, 1, 7)), 1e-9);
    }

    @Test
    void sameFingerprintIsNoOpAndNextWeekAppendsOnlySixRows() {
        WeeklyBackfillProjector first = projector("1957-01-15T00:00:00Z");
        first.project(List.of(), HASH_A, "nodes-v1.0", "r2.0");
        long firstId = snapshotId(1, FIRST_MONDAY);
        int firstCount = snapshotCount();

        first.project(List.of(), HASH_A, "nodes-v1.0", "r2.0");
        assertEquals(firstCount, snapshotCount());
        assertEquals(firstId, snapshotId(1, FIRST_MONDAY));

        WeeklyBackfillProjector nextWeek = projector("1957-01-22T00:00:00Z");
        nextWeek.project(List.of(), HASH_A, "nodes-v1.0", "r2.0");
        assertEquals(firstCount + 6, snapshotCount());
        assertEquals(firstId, snapshotId(1, FIRST_MONDAY));
        assertEquals(6, jdbc.sql("""
                SELECT COUNT(*) FROM pillar_snapshot
                 WHERE snapshot_date = DATE '1957-01-21'
                """).query(Integer.class).single());
    }

    @Test
    void projectorVersionChangeRebuildsHistoricalRows() {
        WeeklyBackfillProjector first = projector(
                "1957-01-15T00:00:00Z", "weekly-projector-v1");
        first.project(List.of(), HASH_A, "nodes-v1.0", "r2.0");
        long firstId = snapshotId(1, FIRST_MONDAY);

        WeeklyBackfillProjector second = projector(
                "1957-01-15T00:00:00Z", "weekly-projector-v2");
        second.project(List.of(), HASH_A, "nodes-v1.0", "r2.0");

        assertEquals(12, snapshotCount());
        assertTrue(firstId != snapshotId(1, FIRST_MONDAY));
        assertTrue(repository.findOpsState(WeeklyBackfillProjector.MARKER_KEY)
                .orElseThrow().value().contains("weekly-projector-v2"));
    }

    @Test
    void matchingOperationalMondayIsPreservedAndMismatchRollsBackRebuild() {
        WeeklyBackfillProjector projector = projector("1957-01-15T00:00:00Z");
        double zeroLogit = LogitEta.logitClipped(0, Params.defaults().epsilon());
        repository.replaceSnapshot(new SnapshotRow(
                0, 1, FIRST_MONDAY, 0, zeroLogit,
                null, null, null, 10, null, null, null, null, "params-v1"));
        long operationalId = snapshotId(1, FIRST_MONDAY);
        projector.project(List.of(), HASH_A, "nodes-v1.0", "r2.0");
        assertEquals(operationalId, snapshotId(1, FIRST_MONDAY));
        int completeCount = snapshotCount();

        repository.replaceSnapshot(new SnapshotRow(
                0, 2, FIRST_MONDAY, 0.10,
                LogitEta.logitClipped(0.10, Params.defaults().epsilon()),
                null, null, null, 10, null, null, null, null, "params-v1"));
        int countBeforeFailure = snapshotCount();
        String markerBeforeFailure = repository.findOpsState(
                WeeklyBackfillProjector.MARKER_KEY).orElseThrow().value();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(
                status -> projector.project(
                        List.of(), HASH_B, "nodes-v1.0", "r2.0")));

        assertEquals(countBeforeFailure, snapshotCount());
        assertTrue(snapshotCount() >= completeCount);
        assertEquals(markerBeforeFailure, repository.findOpsState(
                WeeklyBackfillProjector.MARKER_KEY).orElseThrow().value());
    }

    private WeeklyBackfillProjector projector(String instant) {
        return projector(instant, "weekly-projector-v1");
    }

    private WeeklyBackfillProjector projector(String instant, String version) {
        return new WeeklyBackfillProjector(
                repository,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
                FIRST_MONDAY,
                version);
    }

    private BackfillClaim claim(
            String id,
            String eventType,
            Integer level,
            LocalDate occurredOn,
            ProgramEndEffect endEffect) {
        return new BackfillClaim(
                id, "HC-" + id, "nodes-v1.0", "r2.0", "P6-FUNDING",
                eventType, level, "Test actor", occurredOn, "DAY", "OFFICIAL",
                "Test event", "Boundary fixture.", endEffect,
                endEffect == ProgramEndEffect.CAPABILITY_PROGRAM_END
                        ? "Representative program lineage ended." : null,
                List.of(), new BackfillReview(
                        "APPROVED", "APPROVED", "Checked independently."));
    }

    private double readiness(int pillar, LocalDate date) {
        return jdbc.sql("""
                SELECT readiness FROM pillar_snapshot
                 WHERE pillar = :pillar AND snapshot_date = :snapshotDate
                """).param("pillar", pillar)
                .param("snapshotDate", java.sql.Date.valueOf(date))
                .query(Double.class).single();
    }

    private long snapshotId(int pillar, LocalDate date) {
        return jdbc.sql("""
                SELECT id FROM pillar_snapshot
                 WHERE pillar = :pillar AND snapshot_date = :snapshotDate
                """).param("pillar", pillar)
                .param("snapshotDate", java.sql.Date.valueOf(date))
                .query(Long.class).single();
    }

    private int snapshotCount() {
        return jdbc.sql("SELECT COUNT(*) FROM pillar_snapshot")
                .query(Integer.class).single();
    }
}
