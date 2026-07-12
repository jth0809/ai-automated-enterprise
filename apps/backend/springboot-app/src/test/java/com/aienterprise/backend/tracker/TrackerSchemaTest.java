package com.aienterprise.backend.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void capabilityNodeHasExactlyTwentySeededRows() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM capability_node")
                .query(Integer.class)
                .single();
        assertEquals(20, count);
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
    void sourceRegistryHasExactlySixteenSeededRows() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM source_registry")
                .query(Integer.class)
                .single();
        assertEquals(16, count);
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
}
