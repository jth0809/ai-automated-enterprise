package com.aienterprise.backend.tracker.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class CapabilityGraphRepositoryTest {

    @Autowired
    private CapabilityGraphRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void loadsTheOneActiveGraphWithCanonicalHash() {
        CapabilityGraph graph = repository.loadActive();

        assertEquals("graph-v1.0", graph.version());
        assertEquals("nodes-v1.0", graph.nodeSetVersion());
        assertEquals(29, graph.edges().size());
        assertEquals(graph.declaredSha256(), graph.computedSha256());
    }

    @Test
    void rejectsMultipleActiveGraphs() {
        jdbc.sql("""
                UPDATE capability_graph_version
                   SET active = 'Y'
                 WHERE version_label = 'graph-v0-legacy'
                """).update();

        assertThrows(IllegalStateException.class, repository::loadActive);
    }

    @Test
    void rejectsMissingActiveGraph() {
        jdbc.sql("UPDATE capability_graph_version SET active = 'N'").update();

        assertThrows(IllegalStateException.class, repository::loadActive);
    }
}
