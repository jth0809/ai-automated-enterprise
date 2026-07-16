package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TrackerDagV16SchemaTest {

    private static final String ACTIVE_GRAPH_SHA256 =
            "f5f948b35aa60ce4c72e3550ad188cc4a1e63595096bf64a9da022e7e5313e4e";
    private static final String LEGACY_GRAPH_SHA256 =
            "5d22687ffb5142b1f72e948383e6e431346b78e97546b2e52780e0fec6704fb2";

    @Autowired
    private JdbcClient jdbc;

    @Test
    void preservesLegacyEdgesAndActivatesCorrectMandatoryGraph() {
        assertEquals(2, count("SELECT COUNT(*) FROM capability_graph_version"));
        assertEquals(29, count("""
                SELECT COUNT(*) FROM capability_edge
                 WHERE graph_version_label = 'graph-v0-legacy'
                """));
        assertEquals(29, count("""
                SELECT COUNT(*) FROM capability_edge
                 WHERE graph_version_label = 'graph-v1.0'
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM capability_graph_version
                 WHERE active = 'Y'
                   AND version_label = 'graph-v1.0'
                   AND edge_count = 29
                   AND edge_sha256 = :sha
                """, "sha", ACTIVE_GRAPH_SHA256));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM capability_graph_version
                 WHERE active = 'N'
                   AND version_label = 'graph-v0-legacy'
                   AND edge_count = 29
                   AND edge_sha256 = :sha
                """, "sha", LEGACY_GRAPH_SHA256));

        assertEquals(0, count("""
                SELECT COUNT(*) FROM (
                  SELECT to_node_id, or_group, COUNT(*) grouped_edges
                    FROM capability_edge
                   WHERE graph_version_label = 'graph-v1.0'
                   GROUP BY to_node_id, or_group
                ) grouped
                 WHERE grouped.grouped_edges <> 1
                """));
        assertEquals(6, count("""
                SELECT COUNT(*) FROM (
                  SELECT to_node_id
                    FROM capability_edge
                   WHERE graph_version_label = 'graph-v1.0'
                   GROUP BY to_node_id
                ) targets
                """));
        assertEquals(29, count("""
                SELECT COUNT(*) FROM capability_edge
                 WHERE graph_version_label = 'graph-v0-legacy'
                   AND or_group = 1
                """));
    }

    @Test
    void snapshotAuditColumnsReferenceTheGraphRegistry() {
        jdbc.sql("""
                INSERT INTO pillar_snapshot
                  (pillar, snapshot_date, readiness, raw_readiness,
                   logit_clipped, params_version, graph_version)
                VALUES
                  (1, DATE '2099-01-01', 0.40, 0.60,
                   0.0, 'params-v1', 'graph-v1.0')
                """).update();

        assertEquals(1, count("""
                SELECT COUNT(*) FROM pillar_snapshot
                 WHERE snapshot_date = DATE '2099-01-01'
                   AND raw_readiness = 0.60
                   AND graph_version = 'graph-v1.0'
                """));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO pillar_snapshot
                  (pillar, snapshot_date, readiness, raw_readiness,
                   logit_clipped, params_version, graph_version)
                VALUES
                  (2, DATE '2099-01-01', 0.40, 0.60,
                   0.0, 'params-v1', 'graph-missing')
                """).update());
    }

    @Test
    void graphEdgeConstraintsRejectInvalidRows() {
        long fromId = nodeId("P1-REUSE-LV");
        long toId = nodeId("P1-TRANSPORT-INTEGRATION");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertEdge("graph-v1.0", toId, fromId, 1, -0.001));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertEdge("graph-v1.0", toId, fromId, 1, 0.501));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertEdge("graph-v1.0", toId, fromId, 0, 0.150));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertEdge("graph-v1.0", fromId, fromId, 90, 0.150));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertEdge("graph-v1.0", toId, fromId, 90, 0.150));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertEdge("graph-missing", toId, fromId, 90, 0.150));
    }

    private void insertEdge(
            String graphVersion, long toId, long fromId, int orGroup, double deltaE) {
        jdbc.sql("""
                INSERT INTO capability_edge
                  (to_node_id, from_node_id, or_group, delta_e, graph_version_label)
                VALUES
                  (:toId, :fromId, :orGroup, :deltaE, :graphVersion)
                """)
                .param("toId", toId)
                .param("fromId", fromId)
                .param("orGroup", orGroup)
                .param("deltaE", deltaE)
                .param("graphVersion", graphVersion)
                .update();
    }

    private long nodeId(String code) {
        return jdbc.sql("SELECT id FROM capability_node WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private int count(String sql, String parameter, Object value) {
        return jdbc.sql(sql).param(parameter, value).query(Integer.class).single();
    }
}
