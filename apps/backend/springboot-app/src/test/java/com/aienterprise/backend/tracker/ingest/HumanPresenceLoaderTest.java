package com.aienterprise.backend.tracker.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.LayerBMetric;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class HumanPresenceLoaderTest {

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void importsFourMetricsOnceWithoutTouchingScoringState() {
        int eventsBefore = count("event");
        int historyBefore = count("node_state_history");
        int snapshotsBefore = count("pillar_snapshot");
        int nodeLevelTotalBefore = nodeLevelTotal();
        HumanPresenceLoader loader = productionLoader("human-presence-v1");

        loader.loadIfNeeded();
        loader.loadIfNeeded();

        assertEquals(4, repository.countLayerBMetrics());
        assertEquals(1, importCount("human-presence-v1"));
        assertDecimal("4241.8711", metricValue(
                "ANNUAL_ORBITAL_HUMAN_PERSON_DAYS", LocalDate.of(2024, 12, 31)));
        assertDecimal("19.0000", metricValue(
                "MAX_SIMULTANEOUS_HUMANS_IN_ORBIT", LocalDate.of(2024, 12, 31)));
        assertDecimal("3922.2028", metricValue(
                "ANNUAL_ORBITAL_HUMAN_PERSON_DAYS", LocalDate.of(2025, 12, 31)));
        assertDecimal("14.0000", metricValue(
                "MAX_SIMULTANEOUS_HUMANS_IN_ORBIT", LocalDate.of(2025, 12, 31)));
        assertEquals(eventsBefore, count("event"));
        assertEquals(historyBefore, count("node_state_history"));
        assertEquals(snapshotsBefore, count("pillar_snapshot"));
        assertEquals(nodeLevelTotalBefore, nodeLevelTotal());
    }

    @Test
    void sameVersionWithDifferentBytesFailsHashLock() throws Exception {
        var resource = new ClassPathResource("tracker/human-presence-transitions-v1.csv");
        byte[] original = resource.getContentAsByteArray();
        byte[] changed = (new String(original, UTF_8) + System.lineSeparator()).getBytes(UTF_8);
        HumanPresenceLoader first = new HumanPresenceLoader(
                repository, new ByteArrayResource(original), "human-presence-v1");
        HumanPresenceLoader second = new HumanPresenceLoader(
                repository, new ByteArrayResource(changed), "human-presence-v1");
        first.loadIfNeeded();

        IllegalStateException error = assertThrows(
                IllegalStateException.class, second::loadIfNeeded);

        assertTrue(error.getMessage().contains("hash mismatch"));
        assertEquals(4, repository.countLayerBMetrics());
        assertEquals(1, importCount("human-presence-v1"));
    }

    @Test
    void invalidCsvWritesNeitherMetricsNorImportAudit() {
        String invalid = """
                # dataset_version=human-presence-v7
                # source_label=Reviewed orbital population history
                # source_url=https://planet4589.org/space/astro/web/pop.html
                # accessed_on=2026-07-16
                # complete_through_utc=2025-01-01T00:00:00Z
                timestamp_utc,orbit_population
                2024-02-01T00:00:00Z,10
                2025-01-01T00:00:00Z,10
                """;
        HumanPresenceLoader loader = new HumanPresenceLoader(
                repository, new ByteArrayResource(invalid.getBytes(UTF_8)),
                "human-presence-v7");

        assertThrows(IllegalStateException.class, loader::loadIfNeeded);

        assertEquals(0, repository.countLayerBMetrics());
        assertEquals(0, importCount("human-presence-v7"));
    }

    @Test
    void conflictingNaturalKeyFailsBeforeAnyGeneratedMetricWrite() {
        repository.upsertLayerBMetric(new LayerBMetric(
                0,
                "ANNUAL_ORBITAL_HUMAN_PERSON_DAYS",
                2,
                LocalDate.of(2024, 12, 31),
                new BigDecimal("1.0000"),
                "PERSON_DAYS",
                "MEASURED",
                "Conflicting source",
                "https://example.test/conflict",
                LocalDate.of(2026, 7, 16),
                "a".repeat(64),
                "Conflicting pre-existing metric."));
        HumanPresenceLoader loader = productionLoader("human-presence-v1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, loader::loadIfNeeded);

        assertTrue(error.getMessage().contains("natural-key conflict"));
        assertEquals(1, repository.countLayerBMetrics());
        assertEquals(0, importCount("human-presence-v1"));
    }

    @Test
    void configuredVersionMustMatchReviewedCsvMetadata() {
        HumanPresenceLoader loader = productionLoader("human-presence-v99");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, loader::loadIfNeeded);

        assertTrue(error.getMessage().contains("dataset version mismatch"));
        assertEquals(0, repository.countLayerBMetrics());
        assertEquals(0, importCount("human-presence-v99"));
    }

    private HumanPresenceLoader productionLoader(String version) {
        return new HumanPresenceLoader(repository,
                new ClassPathResource("tracker/human-presence-transitions-v1.csv"), version);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private int nodeLevelTotal() {
        return jdbc.sql("SELECT COALESCE(SUM(current_level), 0) FROM capability_node")
                .query(Integer.class).single();
    }

    private int importCount(String version) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM layer_b_metric_import WHERE dataset_version = :version
                """).param("version", version).query(Integer.class).single();
    }

    private BigDecimal metricValue(String code, LocalDate observedOn) {
        return jdbc.sql("""
                SELECT metric_value FROM layer_b_metric
                 WHERE metric_code = :code AND observed_on = :observedOn
                """).param("code", code).param("observedOn", observedOn)
                .query(BigDecimal.class).single();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
