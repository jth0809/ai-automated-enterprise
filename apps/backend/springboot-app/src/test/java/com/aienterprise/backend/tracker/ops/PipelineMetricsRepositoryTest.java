package com.aienterprise.backend.tracker.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.PipelineMetricRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"tracker.enabled=true", "tracker.backfill-on-boot=false"})
@ActiveProfiles("test")
@Transactional
class PipelineMetricsRepositoryTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Test
    void aggregatesGateRateConfirmedCountAndImpactPercentiles() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        Instant midday = date.atTime(12, 0).toInstant(ZoneOffset.UTC);
        article("1", "GATE_PASSED", midday);
        article("2", "CLASSIFIED", midday);
        article("3", "GATE_REJECTED", midday);
        article("4", "INGESTED", midday);

        event("one", 1.0, midday);
        event("nine", 9.0, midday);
        event("null", null, midday);

        var aggregate = repository.aggregatePipelineMetrics(date);

        assertEquals(2.0 / 3.0, aggregate.relevanceGatePassRate(), 1e-12);
        assertEquals(3, aggregate.confirmedEventCount());
        assertEquals(5.0, aggregate.impactMedian(), 1e-12);
        assertEquals(8.6, aggregate.impactP95(), 1e-12);

        var empty = repository.aggregatePipelineMetrics(date.plusYears(10));
        assertEquals(0.0, empty.relevanceGatePassRate(), 1e-12);
        assertEquals(0, empty.confirmedEventCount());
        assertEquals(0.0, empty.impactMedian(), 1e-12);
        assertEquals(0.0, empty.impactP95(), 1e-12);
    }

    @Test
    void metricUpsertIsIdempotentAndHistoryIsBoundedToTwentyEight() {
        LocalDate start = LocalDate.parse("2026-06-01");
        for (int index = 0; index < 30; index++) {
            LocalDate date = start.plusDays(index);
            repository.upsertPipelineMetric(row(date, index));
        }
        repository.upsertPipelineMetric(row(start.plusDays(29), 99.0));

        assertEquals(30, jdbc.sql("SELECT COUNT(*) FROM pipeline_metric_daily")
                .query(Integer.class).single());
        assertEquals(28, repository.findRecentPipelineMetrics(
                PipelineMonitorJob.IMPACT_MEDIAN, start.plusDays(30), 100).size());
        assertEquals(99.0, repository.findRecentPipelineMetrics(
                PipelineMonitorJob.IMPACT_MEDIAN, start.plusDays(30), 28)
                .getFirst().metricValue(), 1e-12);
    }

    private void article(String suffix, String status, Instant fetchedAt) {
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'NASA'")
                .query(Long.class).single();
        long id = repository.insertArticleIfNew(
                "https://example.test/metric/" + suffix,
                suffix.repeat(64), sourceId, "metric " + suffix,
                fetchedAt, "fixture body").orElseThrow();
        jdbc.sql("""
                UPDATE article SET pipeline_status = :status, fetched_at = :fetchedAt
                 WHERE id = :id
                """)
                .param("status", status)
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .param("id", id)
                .update();
    }

    private void event(String suffix, Double impact, Instant updatedAt) {
        long nodeId = jdbc.sql("SELECT id FROM capability_node WHERE code = 'P1-REUSE-LV'")
                .query(Long.class).single();
        long id = repository.upsertEventByNaturalKey(
                "P1-REUSE-LV|LAB_RESULT|metric-" + suffix + "|2926",
                EventRow.draft(
                        nodeId, "LAB_RESULT", 2, "Agency",
                        LocalDate.parse("2026-07-01"), "OFFICIAL",
                        LocalDate.parse("2026-09-29"),
                        repository.activeRubricVersionId()));
        if (impact != null) {
            repository.recordEventScore(id, impact, 1);
        }
        repository.markEventConfirmed(id, false);
        jdbc.sql("UPDATE event SET updated_at = :updatedAt WHERE id = :id")
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("id", id)
                .update();
    }

    private static PipelineMetricRow row(LocalDate date, double value) {
        return new PipelineMetricRow(
                date, PipelineMonitorJob.IMPACT_MEDIAN, value,
                value, value, value, "OK", false, 0, 14);
    }
}
