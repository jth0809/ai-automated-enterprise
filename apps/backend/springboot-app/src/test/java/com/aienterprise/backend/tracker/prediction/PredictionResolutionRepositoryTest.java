package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
class PredictionResolutionRepositoryTest {

    private static final Instant RESOLVED_AT =
            Instant.parse("2026-07-16T03:00:00Z");

    @Autowired
    private PredictionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void persistsExactHitBrierAndReusesTheSameOutcome() {
        long predictionId = publishedPrediction("a", 0.70);
        long eventId = targetEvent("hit", LocalDate.of(2026, 6, 1));
        PredictionRepository.PendingPrediction pending = repository
                .findForResolution(predictionId).orElseThrow();
        assertEquals(eventId, repository.findFirstTargetTransition(pending)
                .orElseThrow().eventId());
        var draft = PredictionRepository.ResolutionDraft.hit(
                predictionId, eventId, pending.dueOn(), RESOLVED_AT);

        PredictionRepository.ResolutionResult applied = repository.resolve(draft);
        PredictionRepository.ResolutionResult reused = repository.resolve(draft);

        assertEquals(PredictionRepository.ResolutionStatus.APPLIED,
                applied.status());
        assertEquals(0.09, applied.brier(), 1e-12);
        assertEquals(PredictionRepository.ResolutionStatus.REUSED,
                reused.status());
        assertEquals(1, count("SELECT COUNT(*) FROM prediction_resolution_evidence"));
        assertEquals("HIT", scalar("SELECT outcome FROM prediction WHERE id = "
                + predictionId));
    }

    @Test
    void contradictoryOutcomeIsQuarantinedWithoutChangingTheOriginal() {
        long predictionId = publishedPrediction("b", 0.70);
        long eventId = targetEvent("conflict", LocalDate.of(2026, 5, 1));
        var pending = repository.findForResolution(predictionId).orElseThrow();
        repository.resolve(PredictionRepository.ResolutionDraft.hit(
                predictionId, eventId, pending.dueOn(), RESOLVED_AT));

        PredictionRepository.ResolutionResult conflict = repository.resolve(
                PredictionRepository.ResolutionDraft.miss(
                        predictionId, pending.dueOn(), RESOLVED_AT));

        assertEquals(PredictionRepository.ResolutionStatus.CONFLICT,
                conflict.status());
        assertEquals(1, count("SELECT COUNT(*) FROM prediction_resolution_conflict"));
        assertEquals("HIT", scalar("SELECT outcome FROM prediction WHERE id = "
                + predictionId));
        assertEquals(0.09, number("SELECT brier FROM prediction WHERE id = "
                + predictionId), 1e-12);
    }

    @Test
    void repositoryWillNotLetAMissHideAConfirmedTargetCrossing() {
        long predictionId = publishedPrediction("c", 0.40);
        targetEvent("hidden-hit", LocalDate.of(2026, 4, 1));
        var pending = repository.findForResolution(predictionId).orElseThrow();

        assertEquals("confirmed target transition prevents MISS resolution",
                assertThrows(IllegalStateException.class, () ->
                        repository.resolve(PredictionRepository.ResolutionDraft.miss(
                                predictionId, pending.dueOn(), RESOLVED_AT)))
                        .getMessage());
        assertEquals("PENDING", scalar("SELECT outcome FROM prediction WHERE id = "
                + predictionId));
    }

    @Test
    void manualVoidStoresNoBrierAndCannotUseCancellationAsAReason() {
        long predictionId = publishedPrediction("d", 0.40);
        var pending = repository.findForResolution(predictionId).orElseThrow();
        assertThrows(IllegalArgumentException.class, () ->
                new PredictionRepository.ResolutionDraft(
                        predictionId, PredictionRepository.Outcome.VOID, null,
                        pending.dueOn(), RESOLVED_AT, "PROGRAM_CANCELLED",
                        "Cancellation is not a valid VOID reason."));

        repository.resolve(PredictionRepository.ResolutionDraft.voided(
                predictionId, LocalDate.of(2026, 7, 16), RESOLVED_AT,
                "Original predicate meaning cannot be adjudicated."));

        assertEquals("VOID", scalar("SELECT outcome FROM prediction WHERE id = "
                + predictionId));
        assertNull(jdbc.sql("SELECT brier FROM prediction WHERE id = :id")
                .param("id", predictionId).query(Double.class).optional()
                .orElse(null));
    }

    private long publishedPrediction(String suffix, double probability) {
        String calibrationInput = hex(suffix, 64);
        String calibration = "calibration-v1-" + calibrationInput.substring(0, 12);
        jdbc.sql("""
                INSERT INTO prediction_calibration_run
                  (calibration_version, input_sha256, method, calibration_status,
                   sample_count, quarter_count, knots_json, current_result)
                VALUES
                  (:version, :inputHash, 'IDENTITY',
                   'INSUFFICIENT_CALIBRATION_DATA', 0, 0, '[]', 'Y')
                """).param("version", calibration)
                .param("inputHash", calibrationInput).update();
        long nodeId = nodeId("P1-REUSE-LV");
        var candidate = new PredictionCandidateSelector.Candidate(
                nodeId, "P1-REUSE-LV", "완전 재사용 발사체", 1,
                false, 0.18, 5, 6,
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 7, 1),
                12, probability, probability, probability,
                probability * (1 - probability),
                PredictionCandidateSelector.InformationStatus.INFORMATIVE,
                calibration,
                "완전 재사용 발사체가 2026-07-01까지 검증된 수준 L6 이상에 도달한다.",
                3, 42.5, 0.08, 0.07);
        String cohortInput = hex(suffix + "1", 64);
        repository.saveCompleted(new PredictionRepository.CohortDraft(
                "micro-v1-2025-07-01-" + suffix, cohortInput,
                hex(suffix + "2", 64), "nodes-v1.0", "r2.0", "hazard-v1",
                calibration, LocalDate.of(2025, 6, 30),
                LocalDate.of(2025, 7, 1),
                List.of(new PredictionRepository.PublishedCandidate(
                        candidate, hex(suffix + "3", 64), hex(suffix + "4", 64)))));
        return jdbc.sql("""
                SELECT id FROM prediction WHERE input_sha256 = :inputHash
                """).param("inputHash", hex(suffix + "3", 64))
                .query(Long.class).single();
    }

    private long targetEvent(String suffix, LocalDate occurredOn) {
        long nodeId = nodeId("P1-REUSE-LV");
        long rubricId = jdbc.sql("""
                SELECT id FROM rubric_version WHERE version_label = 'r2.0'
                """).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO event
                  (natural_key, node_id, event_type, claimed_level, actor,
                   occurred_on, verification_level, event_status,
                   state_advanced, rubric_version_id)
                VALUES
                  (:key, :nodeId, 'FLIGHT_TEST', 6, 'Fixture', :occurredOn,
                   'OFFICIAL', 'CONFIRMED', 'Y', :rubricId)
                """).param("key", "prediction-target-" + suffix)
                .param("nodeId", nodeId).param("occurredOn", occurredOn)
                .param("rubricId", rubricId).update();
        long eventId = jdbc.sql("SELECT id FROM event WHERE natural_key = :key")
                .param("key", "prediction-target-" + suffix)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO node_state_history
                  (node_id, prev_level, new_level, prev_status, new_status,
                   verification_level, cause_event_id, rubric_version_id)
                VALUES
                  (:nodeId, 5, 6, 'ACTIVE', 'ACTIVE', 'OFFICIAL',
                   :eventId, :rubricId)
                """).param("nodeId", nodeId).param("eventId", eventId)
                .param("rubricId", rubricId).update();
        return eventId;
    }

    private long nodeId(String code) {
        return jdbc.sql("SELECT id FROM capability_node WHERE code = :code")
                .param("code", code).query(Long.class).single();
    }

    private static String hex(String seed, int length) {
        String normalized = seed.toLowerCase().replaceAll("[^0-9a-f]", "a");
        if (normalized.isEmpty()) {
            normalized = "a";
        }
        return normalized.repeat(length).substring(0, length);
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private String scalar(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private double number(String sql) {
        return jdbc.sql(sql).query(Double.class).single();
    }
}
