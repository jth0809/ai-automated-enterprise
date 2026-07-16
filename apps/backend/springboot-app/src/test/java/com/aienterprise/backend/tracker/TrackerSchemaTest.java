package com.aienterprise.backend.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

/**
 * Verifies the Flyway-applied tracker schema (V1__tracker_core.sql) and seed
 * data (V2__tracker_seed.sql) against the real H2 (MODE=Oracle) test
 * database — no mocks. Boots the full Spring context so Flyway actually
 * runs the migrations before assertions execute.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TrackerSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void forecastComparisonServiceStaysAbsentWhenTrackerIsDisabled() {
        assertFalse(applicationContext.containsBean("forecastComparisonService"));
    }

    @Test
    void capabilityNodeHasExactlyThirtyFiveVersionedRows() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM capability_node")
                .query(Integer.class)
                .single();
        assertEquals(35, count);

        Integer versioned = jdbcClient.sql("""
                SELECT COUNT(*) FROM capability_node
                 WHERE node_set_version = 'nodes-v1.0'
                """).query(Integer.class).single();
        assertEquals(35, versioned);
    }

    @Test
    void eachPillarsNodeWeightsSumToOne() {
        for (int pillar = 1; pillar <= 6; pillar++) {
            Double sum = jdbcClient.sql("SELECT SUM(weight) FROM capability_node WHERE pillar = :pillar")
                    .param("pillar", pillar)
                    .query(Double.class)
                    .single();
            assertTrue(Math.abs(sum - 1.0) <= 0.001,
                    "pillar " + pillar + " weight sum expected ~1.0 but was " + sum);
        }
    }

    @Test
    void sourceRegistryHasTheTwentyThreePhaseThreeRows() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM source_registry")
                .query(Integer.class)
                .single();
        assertEquals(23, count);
    }

    @Test
    void phase1bHasExactlyTwelveActiveFeeds() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM source_registry WHERE feed_active = 'Y'")
                .query(Integer.class)
                .single();
        assertEquals(12, count);
    }

    @Test
    void phase1bActivatesTheExpectedFeedCodes() {
        Set<String> activeCodes = new HashSet<>(jdbcClient.sql("""
                SELECT code FROM source_registry WHERE feed_active = 'Y'
                """).query(String.class).list());

        assertEquals(Set.of(
                "NASA", "ESA", "JAXA", "ARXIV", "SPACENEWS", "NASASPACEFLIGHT",
                "SPACEFLIGHT_NOW", "PLANETARY_SOCIETY", "PHYSORG_SPACE", "SPACE_COM",
                "ARSTECHNICA_SPACE", "UNIVERSE_TODAY"), activeCodes);
    }

    @Test
    void everyActiveFeedHasAnActiveFeedDomainPolicy() {
        Integer unbackedFeeds = jdbcClient.sql("""
                SELECT COUNT(*)
                  FROM source_registry s
                 WHERE s.feed_active = 'Y'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM source_domain d
                        WHERE d.source_id = s.id
                          AND d.active = 'Y'
                          AND d.purpose IN ('FEED', 'BOTH')
                   )
                """).query(Integer.class).single();

        assertEquals(0, unbackedFeeds);
    }

    @Test
    void parameterSetHasExactlyOneActiveRow() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM parameter_set WHERE active = 'Y'")
                .query(Integer.class)
                .single();
        assertEquals(1, count);
    }

    @Test
    void rubricVersionHasExactlyOneActiveRow() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM rubric_version WHERE active = 'Y'")
                .query(Integer.class)
                .single();
        assertEquals(1, count);
    }

    @Test
    void kIndexHasAuditableProvenanceAndImportLedger() {
        Set<String> columns = new HashSet<>(jdbcClient.sql("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = 'K_INDEX'
                """).query(String.class).list());

        assertTrue(columns.containsAll(Set.of(
                "PRIMARY_ENERGY_TWH", "SOURCE_URL", "ACCESSED_ON",
                "DATASET_VERSION")));

        Integer importTables = jdbcClient.sql("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                 WHERE TABLE_NAME = 'K_INDEX_IMPORT'
                """).query(Integer.class).single();
        assertEquals(1, importTables);
    }
}
