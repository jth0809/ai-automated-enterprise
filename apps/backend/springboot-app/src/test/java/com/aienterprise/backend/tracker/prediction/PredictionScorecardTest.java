package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class PredictionScorecardTest {

    @Test
    void macroPublishesCohortHorizonPillarAndOverallBrierWithCounts() {
        PredictionScorecard scorecard = PredictionScorecard.from(List.of(
                scored(1, "cohort-a", 1, 12, 0.09),
                scored(2, "cohort-a", 2, 24, 0.49)));

        assertEquals(6, scorecard.groups().size());
        assertEquals(new PredictionScorecard.Group(
                        PredictionScorecard.GroupType.OVERALL, "ALL", 2,
                        0.29, PredictionScorecard.Status.OK),
                scorecard.groups().getFirst());
        assertEquals(1, scorecard.groups().stream().filter(group ->
                group.type() == PredictionScorecard.GroupType.COHORT
                        && group.key().equals("cohort-a")
                        && group.sampleCount() == 2).count());
        assertEquals(2, scorecard.groups().stream().filter(group ->
                group.type() == PredictionScorecard.GroupType.PILLAR).count());
        assertEquals(2, scorecard.groups().stream().filter(group ->
                group.type() == PredictionScorecard.GroupType.HORIZON).count());
    }

    @Test
    void anEmptyTrackRecordIsExplicitlyInsufficient() {
        PredictionScorecard scorecard = PredictionScorecard.from(List.of());

        assertEquals(1, scorecard.groups().size());
        assertEquals(PredictionScorecard.Status.INSUFFICIENT_DATA,
                scorecard.groups().getFirst().status());
        assertEquals(0, scorecard.groups().getFirst().sampleCount());
        assertNull(scorecard.groups().getFirst().meanBrier());
    }

    private static PredictionRepository.ScoredPrediction scored(
            long id, String cohort, int pillar, int horizon, double brier) {
        return new PredictionRepository.ScoredPrediction(
                id, cohort, pillar, horizon, PredictionRepository.Outcome.HIT,
                0.7, brier, LocalDate.of(2026, 7, 1));
    }
}
