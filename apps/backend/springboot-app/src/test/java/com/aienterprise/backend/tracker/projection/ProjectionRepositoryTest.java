package com.aienterprise.backend.tracker.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class ProjectionRepositoryTest {

    @Autowired
    private ProjectionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void persistsExactlySevenRowsAndReadsCompletedCurrentRun() {
        ProjectionInput input = ProjectionTestFixtures.input();
        ProjectionRunResult calculated = new MonteCarloProjector().project(input);

        ProjectionRepository.StoredRun stored =
                repository.saveCompleted(input, calculated);

        assertEquals(1, count("SELECT COUNT(*) FROM projection_run"));
        assertEquals(7, count("SELECT COUNT(*) FROM projection_result"));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM projection_run
                 WHERE run_status = 'COMPLETED' AND current_result = 'Y'
                """));
        assertEquals(stored, repository.findCurrent().orElseThrow());
        assertEquals(stored, repository.findCompletedByInputHash(
                calculated.inputSha256()).orElseThrow());
    }

    @Test
    void matchingInputHashReusesOneCompletedRun() {
        ProjectionInput input = ProjectionTestFixtures.input();
        ProjectionRunResult calculated = new MonteCarloProjector().project(input);

        ProjectionRepository.StoredRun first =
                repository.saveCompleted(input, calculated);
        ProjectionRepository.StoredRun second =
                repository.saveCompleted(input, calculated);

        assertEquals(first, second);
        assertEquals(1, count("SELECT COUNT(*) FROM projection_run"));
        assertEquals(7, count("SELECT COUNT(*) FROM projection_result"));
    }

    @Test
    void preservesNullQuantilesForRightCensoredRows() {
        ProjectionInput input = ProjectionTestFixtures.input(Map.of(
                1, .10, 2, .10, 3, .10, 4, .10, 5, .10, 6, -1.0));
        ProjectionRunResult calculated = new MonteCarloProjector().project(input);

        ProjectionRepository.StoredRun stored =
                repository.saveCompleted(input, calculated);

        ProjectionResult pillarSix = stored.output().results().get(6);
        ProjectionResult overall = stored.output().results().get(0);
        assertNull(pillarSix.etaP10());
        assertNull(pillarSix.etaP50());
        assertNull(pillarSix.etaP90());
        assertNull(overall.etaP50());
        assertEquals(1.0, pillarSix.censoredFraction());
        assertEquals(1.0, overall.censoredFraction());
    }

    @Test
    void publishesNewRunOnlyAfterAllSevenRowsExist() {
        ProjectionInput firstInput = ProjectionTestFixtures.input();
        ProjectionRepository.StoredRun first = repository.saveCompleted(
                firstInput, new MonteCarloProjector().project(firstInput));
        ProjectionInput secondInput = ProjectionTestFixtures.input(Map.of(
                1, .08, 2, .08, 3, .08, 4, .08, 5, .08, 6, .08));

        ProjectionRepository.StoredRun second = repository.saveCompleted(
                secondInput, new MonteCarloProjector().project(secondInput));

        assertEquals(2, count("SELECT COUNT(*) FROM projection_run"));
        assertEquals(14, count("SELECT COUNT(*) FROM projection_result"));
        assertEquals("N", currentFlag(first.id()));
        assertEquals("Y", currentFlag(second.id()));
        assertTrue(second.id() > first.id());
    }

    @Test
    void invalidOutputCannotDeactivatePriorCurrentRun() {
        ProjectionInput firstInput = ProjectionTestFixtures.input();
        ProjectionRepository.StoredRun prior = repository.saveCompleted(
                firstInput, new MonteCarloProjector().project(firstInput));
        ProjectionInput changedInput = ProjectionTestFixtures.input(Map.of(
                1, .08, 2, .08, 3, .08, 4, .08, 5, .08, 6, .08));
        ProjectionRunResult changed = new MonteCarloProjector().project(changedInput);
        ProjectionRunResult mismatched = new ProjectionRunResult(
                "f".repeat(64), changed.seed(), changed.requestedSamples(),
                changed.validSamples(), changed.invalidSamples(),
                changed.diagnostics(), changed.results());

        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCompleted(changedInput, mismatched));

        assertEquals(prior, repository.findCurrent().orElseThrow());
        assertEquals(1, count("SELECT COUNT(*) FROM projection_run"));
        assertEquals(7, count("SELECT COUNT(*) FROM projection_result"));
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private String currentFlag(long id) {
        return jdbc.sql("SELECT current_result FROM projection_run WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }
}
