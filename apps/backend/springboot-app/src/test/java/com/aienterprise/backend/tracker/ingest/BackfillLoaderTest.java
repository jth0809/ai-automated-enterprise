package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tracker.enabled=true",
                "tracker.backfill-on-boot=false",
                "tracker.backfill-resource=tracker/backfill-sample.json"})
@ActiveProfiles("test")
class BackfillLoaderTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private BackfillLoader loader;

    // This test commits (the loader is boot-time code, not request-scoped), so
    // restore the shared in-memory database for the other integration suites.
    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM node_state_history").update();
        jdbc.sql("DELETE FROM pillar_snapshot").update();
        jdbc.sql("DELETE FROM event").update();
        jdbc.sql("""
                UPDATE capability_node
                   SET current_level = 0, verification_level = NULL,
                       node_status = 'ACTIVE', dormant_since = NULL
                """).update();
    }

    @Test
    void replayRebuildsNodeLevelsSnapshotsAndIsIdempotent() {
        loader.loadIfEmpty();

        // Node levels replay to the last claimed level per node.
        assertEquals(9, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
        assertEquals(6, repository.findNodeByCode("P4-ISRU-PROP").currentLevel());
        assertEquals(4, repository.findNodeByCode("P6-GOV-FRAMEWORK").currentLevel());

        // All backfill events land as CONFIRMED historical facts.
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM event WHERE event_status = 'CONFIRMED'")
                .query(Integer.class).single());

        // Year-end snapshots run 1960 through last complete year, monotonically
        // non-decreasing per pillar (levels only ever advance during replay).
        int expectedYears = LocalDate.now(ZoneOffset.UTC).getYear() - 1960;
        assertEquals(expectedYears, jdbc.sql("SELECT COUNT(*) FROM pillar_snapshot WHERE pillar = 1")
                .query(Integer.class).single());
        List<Double> pillarOne = jdbc.sql(
                "SELECT readiness FROM pillar_snapshot WHERE pillar = 1 ORDER BY snapshot_date")
                .query(Double.class).list();
        for (int i = 1; i < pillarOne.size(); i++) {
            assertTrue(pillarOne.get(i) >= pillarOne.get(i - 1),
                    "pillar 1 readiness regressed at index " + i);
        }
        assertTrue(pillarOne.get(pillarOne.size() - 1) > pillarOne.get(0));

        // Re-running against a populated database is a no-op.
        loader.loadIfEmpty();
        assertEquals(8, jdbc.sql("SELECT COUNT(*) FROM event").query(Integer.class).single());
        assertEquals(expectedYears, jdbc.sql("SELECT COUNT(*) FROM pillar_snapshot WHERE pillar = 1")
                .query(Integer.class).single());
    }
}
