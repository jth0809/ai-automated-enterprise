package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class PredictionRepositoryTest {

    @Autowired
    private PredictionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void loadsTheVersionedHazardParameters() {
        assertEquals(HazardParameters.defaults(), repository.loadActiveParameters());
    }

    @Test
    void atomicallyPublishesAndReusesAnIdenticalCohort() {
        String calibration = insertCalibration("a".repeat(64));
        PredictionRepository.CohortDraft draft = draft(
                "b".repeat(64), calibration, nodeId("P1-REUSE-LV"));

        PredictionRepository.StoredCohort first = repository.saveCompleted(draft);
        PredictionRepository.StoredCohort second = repository.saveCompleted(draft);

        assertEquals(first, second);
        assertEquals(1, count("SELECT COUNT(*) FROM prediction_cohort"));
        assertEquals(1, count("SELECT COUNT(*) FROM prediction"));
        assertEquals("COMPLETED", scalar("""
                SELECT cohort_status FROM prediction_cohort WHERE id = %d
                """.formatted(first.id())));
        assertEquals(first, repository.findCompletedByInputHash(
                draft.inputSha256()).orElseThrow());
    }

    @Test
    void preservesPublicationRankWhenItDiffersFromNodeSortOrder() {
        String calibration = insertCalibration("4".repeat(64));
        var firstPublished = published(
                nodeId("P2-ECLSS"), "P2-ECLSS", "폐쇄 순환 생명 유지", 2,
                calibration, "5".repeat(64), "6".repeat(64));
        var secondPublished = published(
                nodeId("P1-REUSE-LV"), "P1-REUSE-LV", "완전 재사용 발사체", 1,
                calibration, "7".repeat(64), "8".repeat(64));
        PredictionRepository.CohortDraft draft = new PredictionRepository.CohortDraft(
                "micro-v1-2026-07-16-ranked", "9".repeat(64), "a".repeat(64),
                "nodes-v1.0", "r2.0", "hazard-v1", calibration,
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 16),
                List.of(firstPublished, secondPublished));

        PredictionRepository.StoredCohort first = repository.saveCompleted(draft);
        PredictionRepository.StoredCohort reused = repository.saveCompleted(draft);

        assertEquals(first, reused);
        assertEquals(List.of("P2-ECLSS", "P1-REUSE-LV"),
                reused.predictions().stream()
                        .map(value -> value.candidate().nodeCode()).toList());
    }

    @Test
    void persistenceFailureRollsBackWithoutTouchingPriorCohorts() {
        String calibration = insertCalibration("c".repeat(64));
        PredictionRepository.StoredCohort prior = repository.saveCompleted(
                draft("d".repeat(64), calibration, nodeId("P1-REUSE-LV")));
        PredictionRepository.CohortDraft invalid = draft(
                "e".repeat(64), calibration, 999_999L);

        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThrows(DataIntegrityViolationException.class, () ->
                nested.executeWithoutResult(ignored ->
                        repository.saveCompleted(invalid)));

        assertEquals(1, count("SELECT COUNT(*) FROM prediction_cohort"));
        assertEquals(prior, repository.findCompletedByInputHash(
                prior.inputSha256()).orElseThrow());
    }

    @Test
    void publicReaderReturnsOnlyCompletedCohortsWithFullAuditDiagnostics() {
        String calibration = insertCalibration("f".repeat(64));
        PredictionRepository.CohortDraft draft = draft(
                "1".repeat(64), calibration, nodeId("P1-REUSE-LV"));
        repository.saveCompleted(draft);

        PredictionRepository.PublishedCohort cohort = repository
                .findPublishedCohorts().getFirst();
        PredictionRepository.PublishedPrediction prediction = cohort
                .predictions().getFirst();

        assertEquals(draft.inputSha256(), cohort.inputSha256());
        assertEquals(1, cohort.predictions().size());
        assertEquals("P1-REUSE-LV", prediction.nodeCode());
        assertEquals(0.35123457, prediction.rawProbability(), 1e-12);
        assertEquals(0.351235, prediction.issuedProbability(), 1e-12);
        assertEquals(42.5123456789, prediction.exposureYears(), 1e-12);
        assertEquals("PENDING", prediction.outcome().name());
        assertEquals("PENDING", prediction.resolutionStatus());
        assertNull(prediction.brier());
        assertNull(prediction.resolvedAt());
        assertEquals("8".repeat(64), prediction.inputSha256());
        assertEquals("7".repeat(64), prediction.statementSha256());
    }

    private PredictionRepository.CohortDraft draft(
            String inputHash,
            String calibrationVersion,
            long nodeId) {
        var published = published(
                nodeId, "P1-REUSE-LV", "완전 재사용 발사체", 1,
                calibrationVersion, "8".repeat(64), "7".repeat(64));
        return new PredictionRepository.CohortDraft(
                "micro-v1-2026-07-16-" + inputHash.substring(0, 12),
                inputHash, "9".repeat(64), "nodes-v1.0", "r2.0",
                "hazard-v1", calibrationVersion,
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 16),
                List.of(published));
    }

    private PredictionRepository.PublishedCandidate published(
            long nodeId,
            String nodeCode,
            String nodeName,
            int pillar,
            String calibrationVersion,
            String inputHash,
            String statementHash) {
        var candidate = new PredictionCandidateSelector.Candidate(
                nodeId, nodeCode, nodeName, pillar,
                false, 0.180123456789, 5, 6,
                LocalDate.of(2026, 7, 16), LocalDate.of(2028, 1, 16),
                18, 0.351234567891, 0.351234567891, 0.351234567891,
                0.227512345678,
                PredictionCandidateSelector.InformationStatus.INFORMATIVE,
                calibrationVersion,
                nodeName + "이 2028-01-16까지 검증된 수준 L6 이상에 도달한다.",
                3, 42.512345678901, 0.0812345678912, 0.0712345678912);
        return new PredictionRepository.PublishedCandidate(
                candidate, inputHash, statementHash);
    }

    private String insertCalibration(String inputHash) {
        String version = "calibration-v1-" + inputHash.substring(0, 12);
        jdbc.sql("""
                INSERT INTO prediction_calibration_run
                  (calibration_version, input_sha256, method, calibration_status,
                   sample_count, quarter_count, knots_json, current_result)
                VALUES
                  (:version, :inputHash, 'IDENTITY',
                   'INSUFFICIENT_CALIBRATION_DATA', 0, 0, '[]', 'Y')
                """).param("version", version).param("inputHash", inputHash)
                .update();
        return version;
    }

    private long nodeId(String code) {
        return jdbc.sql("SELECT id FROM capability_node WHERE code = :code")
                .param("code", code).query(Long.class).single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private String scalar(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }
}
