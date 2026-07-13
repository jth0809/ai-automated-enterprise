package com.aienterprise.backend.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TrackerNodesV1Test {

    private static final Set<String> EXPECTED_CODES = Set.of(
            "P1-REUSE-LV", "P1-ORBIT-REFUEL", "P1-DEEP-PROP",
            "P1-EDL-HEAVY", "P1-SURFACE-ASCENT", "P1-CREW-SAFE",
            "P1-ORBIT-LOGISTICS", "P1-TRANSPORT-INTEGRATION",
            "P2-ECLSS", "P2-FOOD", "P2-RAD", "P2-MED",
            "P2-WASTE-CYCLE", "P2-HEALTH-AUTONOMY", "P2-SURVIVAL-INTEGRATION",
            "P3-CONSTRUCT", "P3-POWER", "P3-COMMS", "P3-THERMAL",
            "P3-DUST", "P3-HABITAT-INTEGRATION",
            "P4-ISRU-PROP", "P4-NUKE", "P4-MATERIALS",
            "P4-MANUFACTURING", "P4-RESOURCE-INTEGRATION",
            "P5-AUTOCON", "P5-AUTONOMY", "P5-MAINTENANCE",
            "P5-OPS-INTEGRATION",
            "P6-LAUNCH-MARKET", "P6-GOV-FRAMEWORK", "P6-FUNDING",
            "P6-INSURANCE-STD", "P6-SETTLEMENT-INTEGRATION");

    private static final Map<String, Double> EXPECTED_WEIGHTS = Map.ofEntries(
            entry("P1-REUSE-LV", 0.18),
            entry("P1-ORBIT-REFUEL", 0.16),
            entry("P1-DEEP-PROP", 0.12),
            entry("P1-EDL-HEAVY", 0.14),
            entry("P1-SURFACE-ASCENT", 0.10),
            entry("P1-CREW-SAFE", 0.12),
            entry("P1-ORBIT-LOGISTICS", 0.08),
            entry("P1-TRANSPORT-INTEGRATION", 0.10),
            entry("P2-ECLSS", 0.24),
            entry("P2-FOOD", 0.16),
            entry("P2-RAD", 0.12),
            entry("P2-MED", 0.12),
            entry("P2-WASTE-CYCLE", 0.12),
            entry("P2-HEALTH-AUTONOMY", 0.10),
            entry("P2-SURVIVAL-INTEGRATION", 0.14),
            entry("P3-CONSTRUCT", 0.23),
            entry("P3-POWER", 0.22),
            entry("P3-COMMS", 0.15),
            entry("P3-THERMAL", 0.12),
            entry("P3-DUST", 0.10),
            entry("P3-HABITAT-INTEGRATION", 0.18),
            entry("P4-ISRU-PROP", 0.30),
            entry("P4-NUKE", 0.22),
            entry("P4-MATERIALS", 0.14),
            entry("P4-MANUFACTURING", 0.16),
            entry("P4-RESOURCE-INTEGRATION", 0.18),
            entry("P5-AUTOCON", 0.28),
            entry("P5-AUTONOMY", 0.27),
            entry("P5-MAINTENANCE", 0.20),
            entry("P5-OPS-INTEGRATION", 0.25),
            entry("P6-LAUNCH-MARKET", 0.27),
            entry("P6-GOV-FRAMEWORK", 0.24),
            entry("P6-FUNDING", 0.17),
            entry("P6-INSURANCE-STD", 0.14),
            entry("P6-SETTLEMENT-INTEGRATION", 0.18));

    private static final Map<String, Set<String>> EXPECTED_DEPENDENCIES = Map.of(
            "P1-TRANSPORT-INTEGRATION", Set.of(
                    "P1-REUSE-LV", "P1-ORBIT-REFUEL", "P1-DEEP-PROP",
                    "P1-EDL-HEAVY", "P1-SURFACE-ASCENT", "P1-CREW-SAFE",
                    "P1-ORBIT-LOGISTICS"),
            "P2-SURVIVAL-INTEGRATION", Set.of(
                    "P2-ECLSS", "P2-FOOD", "P2-RAD", "P2-MED",
                    "P2-WASTE-CYCLE", "P2-HEALTH-AUTONOMY"),
            "P3-HABITAT-INTEGRATION", Set.of(
                    "P3-CONSTRUCT", "P3-POWER", "P3-COMMS", "P3-THERMAL", "P3-DUST"),
            "P4-RESOURCE-INTEGRATION", Set.of(
                    "P4-ISRU-PROP", "P4-NUKE", "P4-MATERIALS", "P4-MANUFACTURING"),
            "P5-OPS-INTEGRATION", Set.of(
                    "P5-AUTOCON", "P5-AUTONOMY", "P5-MAINTENANCE"),
            "P6-SETTLEMENT-INTEGRATION", Set.of(
                    "P6-LAUNCH-MARKET", "P6-GOV-FRAMEWORK", "P6-FUNDING", "P6-INSURANCE-STD"));

    @Autowired
    private JdbcClient jdbc;

    @Test
    void nodesV1HasExactlyThirtyFiveRows() {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM capability_node
                 WHERE node_set_version = 'nodes-v1.0'
                """).query(Integer.class).single();

        assertEquals(35, count);
    }

    @Test
    void nodesV1UsesTheExactApprovedCodeSet() {
        Set<String> actual = Set.copyOf(jdbc.sql("""
                SELECT code FROM capability_node
                 WHERE node_set_version = 'nodes-v1.0'
                """).query(String.class).list());

        assertEquals(EXPECTED_CODES, actual);
    }

    @Test
    void nodesV1UsesTheExactApprovedWeights() {
        Map<String, Double> actual = jdbc.sql("""
                SELECT code, weight FROM capability_node
                 WHERE node_set_version = 'nodes-v1.0'
                """).query((rs, rowNum) -> entry(rs.getString("code"), rs.getDouble("weight")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertEquals(EXPECTED_WEIGHTS.keySet(), actual.keySet());
        EXPECTED_WEIGHTS.forEach((code, expected) ->
                assertEquals(expected, actual.get(code), 0.0001, code));
    }

    @Test
    void everyPillarWeightSumIsOne() {
        for (int pillar = 1; pillar <= 6; pillar++) {
            Double sum = jdbc.sql("""
                    SELECT COALESCE(SUM(weight), 0) FROM capability_node
                     WHERE node_set_version = 'nodes-v1.0'
                       AND pillar = :pillar
                    """).param("pillar", pillar).query(Double.class).single();

            assertEquals(1.0, sum, 0.001, "pillar " + pillar);
        }
    }

    @Test
    void everyPillarHasExactlyOneIntegrationNode() {
        for (int pillar = 1; pillar <= 6; pillar++) {
            Integer count = jdbc.sql("""
                    SELECT COUNT(*) FROM capability_node
                     WHERE node_set_version = 'nodes-v1.0'
                       AND pillar = :pillar
                       AND is_integration_node = 'Y'
                    """).param("pillar", pillar).query(Integer.class).single();

            assertEquals(1, count, "pillar " + pillar);
        }
    }

    @Test
    void technologyAndEconomicNodesUseTheirApprovedScales() {
        Integer invalid = jdbc.sql("""
                SELECT COUNT(*) FROM capability_node
                 WHERE node_set_version = 'nodes-v1.0'
                   AND ((pillar BETWEEN 1 AND 5 AND scale_type <> 'TRL')
                     OR (pillar = 6 AND scale_type <> 'EGL'))
                """).query(Integer.class).single();

        assertEquals(0, invalid);
    }

    @Test
    void everyNodeHasAnAuditableBoundaryDescription() {
        List<String> descriptions = jdbc.sql("""
                SELECT description FROM capability_node
                 WHERE node_set_version = 'nodes-v1.0'
                """).query(String.class).list();

        assertEquals(35, descriptions.size());
        assertFalse(descriptions.stream().anyMatch(
                description -> description == null || description.isBlank()));
    }

    @Test
    void integrationNodesHaveEveryApprovedElementAsMandatoryDependencies() {
        List<Edge> edges = jdbc.sql("""
                SELECT source.code AS from_code,
                       target.code AS to_code,
                       edge.or_group,
                       edge.delta_e
                  FROM capability_edge edge
                  JOIN capability_node source ON source.id = edge.from_node_id
                  JOIN capability_node target ON target.id = edge.to_node_id
                 WHERE target.node_set_version = 'nodes-v1.0'
                """).query((rs, rowNum) -> new Edge(
                        rs.getString("from_code"),
                        rs.getString("to_code"),
                        rs.getInt("or_group"),
                        rs.getDouble("delta_e")))
                .list();

        assertEquals(29, edges.size());
        assertTrue(edges.stream().allMatch(edge -> edge.orGroup() == 1));
        assertTrue(edges.stream().allMatch(edge -> Math.abs(edge.deltaE() - 0.150) < 0.0001));

        Map<String, Set<String>> actual = edges.stream().collect(Collectors.groupingBy(
                Edge::toCode,
                Collectors.mapping(Edge::fromCode, Collectors.toSet())));
        assertEquals(EXPECTED_DEPENDENCIES, actual);
    }

    @Test
    void rubricR2IsActiveForNodesV1() {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM rubric_version
                 WHERE version_label = 'r2.0'
                   AND node_set_version = 'nodes-v1.0'
                   AND active = 'Y'
                """).query(Integer.class).single();

        assertEquals(1, count);
    }

    private record Edge(String fromCode, String toCode, int orGroup, double deltaE) {
    }
}
