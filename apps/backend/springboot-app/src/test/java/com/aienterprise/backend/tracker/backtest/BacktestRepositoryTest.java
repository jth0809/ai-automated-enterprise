package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class BacktestRepositoryTest {

    @Autowired
    private BacktestRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void publishesOnlyAfterEverySelectedFoldAndMetricIsStored() {
        BacktestReport report = BacktestTestFixtures.report();

        BacktestRepository.StoredRun stored = repository.saveCompleted(report);

        assertEquals(1, count("SELECT COUNT(*) FROM backtest_run"));
        assertEquals(report.folds().size(), count(
                "SELECT COUNT(*) FROM backtest_fold"));
        assertEquals(35, count("SELECT COUNT(*) FROM backtest_metric"));
        assertEquals(91, count(
                "SELECT COUNT(*) FROM backtest_model_evaluation"));
        assertEquals(stored, repository.findCurrent().orElseThrow());
        assertEquals(stored, repository.findCompletedByInputHash(
                report.inputSha256()).orElseThrow());
        assertEquals("COMPLETED", scalar(
                "SELECT run_status FROM backtest_run WHERE id = " + stored.id()));
        assertEquals("Y", scalar(
                "SELECT current_result FROM backtest_run WHERE id = " + stored.id()));
    }

    @Test
    void matchingInputHashReusesTheCompletedAuditRun() {
        BacktestReport report = BacktestTestFixtures.report();

        BacktestRepository.StoredRun first = repository.saveCompleted(report);
        BacktestRepository.StoredRun second = repository.saveCompleted(report);

        assertEquals(first, second);
        assertEquals(1, count("SELECT COUNT(*) FROM backtest_run"));
        assertEquals(report.folds().size(), count(
                "SELECT COUNT(*) FROM backtest_fold"));
    }

    @Test
    void aMalformedReplacementCannotDeactivateThePriorCurrentRun() {
        BacktestRepository.StoredRun prior = repository.saveCompleted(
                BacktestTestFixtures.report());
        List<BacktestReport.FoldResult> duplicateFolds = new ArrayList<>(
                prior.report().folds());
        duplicateFolds.add(duplicateFolds.getFirst());
        BacktestReport malformed = copy(
                prior.report(), "b".repeat(64), duplicateFolds);

        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCompleted(malformed));

        assertEquals(prior, repository.findCurrent().orElseThrow());
        assertEquals(1, count("SELECT COUNT(*) FROM backtest_run"));
    }

    @Test
    void aCompleteReplacementAtomicallyBecomesCurrent() {
        BacktestRepository.StoredRun prior = repository.saveCompleted(
                BacktestTestFixtures.report());
        BacktestReport replacement = copy(
                prior.report(), "c".repeat(64), prior.report().folds());

        BacktestRepository.StoredRun current = repository.saveCompleted(replacement);

        assertEquals(2, count("SELECT COUNT(*) FROM backtest_run"));
        assertEquals("N", scalar(
                "SELECT current_result FROM backtest_run WHERE id = " + prior.id()));
        assertEquals("Y", scalar(
                "SELECT current_result FROM backtest_run WHERE id = " + current.id()));
        assertEquals(current, repository.findCurrent().orElseThrow());
    }

    private static BacktestReport copy(
            BacktestReport report,
            String inputHash,
            List<BacktestReport.FoldResult> folds) {
        return new BacktestReport(
                report.reportVersion(), inputHash, report.seed(),
                report.datasetSha256(), report.nodeSetVersion(),
                report.rubricVersion(), report.paramsVersion(),
                report.graphVersion(), report.candidateRegistryVersion(),
                report.asOf(), report.calibrationStart(), report.calibrationEnd(),
                report.holdoutStart(), report.holdoutEnd(), report.horizonWeeks(),
                report.sampleCount(), report.calibrationCutoffCount(),
                report.holdoutCutoffCount(), report.selectedCandidate(),
                report.objectiveScore(), report.calibrationCandidates(), folds,
                report.metrics(), report.modelEvaluations());
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private String scalar(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }
}
