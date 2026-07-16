package com.aienterprise.backend.tracker.graph;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class CapabilityGraphRepository {

    private final JdbcClient jdbc;

    public CapabilityGraphRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CapabilityGraph loadActive() {
        List<GraphHeader> headers = jdbc.sql("""
                SELECT version_label, node_set_version, edge_sha256, edge_count
                  FROM capability_graph_version
                 WHERE active = 'Y'
                 ORDER BY version_label
                """)
                .query((rs, rowNum) -> new GraphHeader(
                        rs.getString("version_label"),
                        rs.getString("node_set_version"),
                        rs.getString("edge_sha256"),
                        rs.getInt("edge_count")))
                .list();
        if (headers.size() != 1) {
            throw new IllegalStateException(
                    "exactly one active capability graph is required; found " + headers.size());
        }

        GraphHeader header = headers.getFirst();
        List<CapabilityEdgeRow> edges = jdbc.sql("""
                SELECT edge.graph_version_label,
                       source.code AS from_code,
                       target.code AS to_code,
                       edge.or_group,
                       edge.delta_e
                  FROM capability_edge edge
                  JOIN capability_node source ON source.id = edge.from_node_id
                  JOIN capability_node target ON target.id = edge.to_node_id
                 WHERE edge.graph_version_label = :version
                 ORDER BY target.code, edge.or_group, source.code
                """)
                .param("version", header.version())
                .query((rs, rowNum) -> new CapabilityEdgeRow(
                        rs.getString("graph_version_label"),
                        rs.getString("from_code"),
                        rs.getString("to_code"),
                        rs.getInt("or_group"),
                        rs.getDouble("delta_e")))
                .list();

        return new CapabilityGraph(
                header.version(),
                header.nodeSetVersion(),
                header.edgeSha256(),
                header.edgeCount(),
                edges);
    }

    private record GraphHeader(
            String version,
            String nodeSetVersion,
            String edgeSha256,
            int edgeCount) {
    }
}
