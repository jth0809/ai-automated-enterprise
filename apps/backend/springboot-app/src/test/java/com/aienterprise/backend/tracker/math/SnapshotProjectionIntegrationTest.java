package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ingest.BackfillLoader;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tracker.enabled=true",
                "tracker.phase4-projection-enabled=true",
                "tracker.backfill-on-boot=false",
                "tracker.backfill-resource=tracker/backfill-sample.json",
                "tracker.backfill-candidates-resource=tracker/backfill/historical-candidates-import.jsonl",
                "tracker.backfill-dataset-version=backfill-test-v1"})
@ActiveProfiles("test")
@Transactional
class SnapshotProjectionIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository trackerRepository;

    @Autowired
    private BackfillLoader loader;

    @Autowired
    private SnapshotJob job;

    @BeforeEach
    void useFastApprovedSampleBound() {
        jdbc.sql("""
                UPDATE parameter_uncertainty
                   SET central_value = 1000
                 WHERE parameter_name = 'mc_samples'
                   AND parameter_set_id = (
                     SELECT id FROM parameter_set WHERE active = 'Y')
                """).update();
    }

    @Test
    void enabledSnapshotPersistsProjectionAndUsesItsQuantiles() {
        loader.loadIfEmpty();

        job.snapshotNow();

        assertEquals(1, count("""
                SELECT COUNT(*) FROM projection_run
                 WHERE run_status = 'COMPLETED'
                   AND current_result = 'Y'
                   AND sample_count = 1000
                """));
        assertEquals(7, count("SELECT COUNT(*) FROM projection_result"));
        for (int pillar = 0; pillar <= 6; pillar++) {
            SnapshotRow snapshot = trackerRepository.findLatestSnapshot(pillar)
                    .orElseThrow();
            StoredQuantiles projection = quantiles(pillar);
            assertNullableNumberEquals(projection.p50(), snapshot.etaYear());
            assertNullableNumberEquals(projection.p10(), snapshot.etaLow());
            assertNullableNumberEquals(projection.p90(), snapshot.etaHigh());
        }
    }

    @Test
    void rerunningSameSnapshotReusesOneCanonicalProjection() {
        loader.loadIfEmpty();

        job.snapshotNow();
        String firstHash = currentHash();
        job.snapshotNow();

        assertEquals(firstHash, currentHash());
        assertEquals(1, count("SELECT COUNT(*) FROM projection_run"));
        assertEquals(7, count("SELECT COUNT(*) FROM projection_result"));
    }

    @Test
    void failedSnapshotLeavesPriorProjectionCurrent() {
        loader.loadIfEmpty();
        job.snapshotNow();
        String priorHash = currentHash();
        jdbc.sql("UPDATE parameter_set SET active = 'N'").update();

        assertThrows(IllegalStateException.class, job::snapshotNow);

        assertEquals(priorHash, currentHash());
        assertEquals(1, count("""
                SELECT COUNT(*) FROM projection_run
                 WHERE run_status = 'COMPLETED' AND current_result = 'Y'
                """));
    }

    private StoredQuantiles quantiles(int pillar) {
        return jdbc.sql("""
                SELECT r.eta_p10, r.eta_p50, r.eta_p90
                  FROM projection_result r
                  JOIN projection_run run ON run.id = r.run_id
                 WHERE run.current_result = 'Y'
                   AND run.run_status = 'COMPLETED'
                   AND r.pillar = :pillar
                """)
                .param("pillar", pillar)
                .query((rs, rowNum) -> new StoredQuantiles(
                        rs.getBigDecimal("eta_p10"),
                        rs.getBigDecimal("eta_p50"),
                        rs.getBigDecimal("eta_p90")))
                .single();
    }

    private String currentHash() {
        return jdbc.sql("""
                SELECT input_sha256 FROM projection_run
                 WHERE current_result = 'Y' AND run_status = 'COMPLETED'
                """).query(String.class).single().trim();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static void assertNullableNumberEquals(
            BigDecimal expected,
            Double actual) {
        if (expected == null) {
            assertEquals(null, actual);
        } else {
            assertEquals(expected.doubleValue(), actual, 1e-9);
        }
    }

    private record StoredQuantiles(
            BigDecimal p10,
            BigDecimal p50,
            BigDecimal p90) {
    }
}
