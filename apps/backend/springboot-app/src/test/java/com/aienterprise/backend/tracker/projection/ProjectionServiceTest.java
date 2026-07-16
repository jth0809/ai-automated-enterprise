package com.aienterprise.backend.tracker.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.phase4-projection-enabled=true")
@ActiveProfiles("test")
@Transactional
class ProjectionServiceTest {

    @Autowired
    private ProjectionService service;

    @Autowired
    private ProjectionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void insertAuditableDatasetImport() {
        jdbc.sql("""
                INSERT INTO backfill_import
                  (dataset_version, dataset_sha256, node_set_version,
                   rubric_version_id, record_count)
                SELECT 'zz-projection-test-v1', :datasetHash, 'nodes-v1.0', id, 6
                  FROM rubric_version
                 WHERE version_label = 'r2.0'
                """)
                .param("datasetHash", "b".repeat(64))
                .update();
    }

    @Test
    void resolvesLatestDatasetRunsAndReusesMatchingInput() {
        ProjectionService.State state = state(ProjectionTestFixtures.input());

        ProjectionRunResult first = service.run(state);
        ProjectionRunResult second = service.run(state);

        assertEquals(first, second);
        assertEquals("b".repeat(64), repository.findCurrent()
                .orElseThrow().datasetSha256());
        assertEquals(1, count("SELECT COUNT(*) FROM projection_run"));
        assertEquals(7, count("SELECT COUNT(*) FROM projection_result"));
    }

    @Test
    void refusesProjectionWhenNoAuditableBackfillImportExists() {
        jdbc.sql("DELETE FROM backfill_import").update();

        assertThrows(IllegalStateException.class,
                () -> service.run(state(ProjectionTestFixtures.input())));
        assertEquals(0, count("SELECT COUNT(*) FROM projection_run"));
    }

    @Test
    void calculationFailureLeavesPriorCompletedCurrentRunUntouched() {
        ProjectionRunResult prior = service.run(
                state(ProjectionTestFixtures.input()));
        ProjectionService failing = new ProjectionService(
                repository,
                new MonteCarloProjector() {
                    @Override
                    public ProjectionRunResult project(ProjectionInput input) {
                        throw new IllegalStateException("synthetic calculation failure");
                    }
                });
        ProjectionInput changed = ProjectionTestFixtures.input(Map.of(
                1, .08, 2, .08, 3, .08, 4, .08, 5, .08, 6, .08));

        assertThrows(IllegalStateException.class,
                () -> failing.run(state(changed)));

        assertEquals(prior.inputSha256(), repository.findCurrent()
                .orElseThrow().output().inputSha256());
        assertEquals(1, count("SELECT COUNT(*) FROM projection_run"));
        assertEquals(7, count("SELECT COUNT(*) FROM projection_result"));
    }

    private static ProjectionService.State state(ProjectionInput input) {
        return new ProjectionService.State(
                input.asOf(), input.nodes(), input.graph(), input.model(),
                input.centralReadiness(), input.trends(), input.momentum(),
                input.targetReadiness());
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
