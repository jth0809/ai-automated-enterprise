package com.aienterprise.backend.tracker.scoring;

import static com.aienterprise.backend.tracker.event.VerificationLevel.CLAIMED;
import static com.aienterprise.backend.tracker.event.VerificationLevel.OFFICIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImpactScorerTest {

    @Test
    void officialSixToSevenFlightTestScoresSevenPointTwoWithoutReview() {
        ScoreResult result = ImpactScorer.score("FLIGHT_TEST", 7, 6, false, OFFICIAL);

        assertEquals(7.2, result.impactScore(), 0.0001);
        assertEquals(1, result.novelty());
        assertTrue(result.stateEligible());
        assertFalse(result.requiresReview());
    }

    @Test
    void reachingLevelEightAlwaysRequiresReview() {
        ScoreResult result = ImpactScorer.score("OPERATIONAL_DEPLOYMENT", 8, 7, false, OFFICIAL);

        assertEquals(8.1, result.impactScore(), 0.0001);
        assertTrue(result.requiresReview());
    }

    @Test
    void repeatedDemonstrationHasNoNoveltyOrImpact() {
        ScoreResult result = ImpactScorer.score("FLIGHT_TEST", 6, 7, false, OFFICIAL);

        assertEquals(0, result.novelty());
        assertEquals(0, result.impactScore(), 0.0001);
        assertFalse(result.stateEligible());
    }

    @Test
    void dormantCapabilityRedemonstrationIsNovel() {
        ScoreResult result = ImpactScorer.score("FLIGHT_TEST", 7, 9, true, OFFICIAL);

        assertEquals(1, result.novelty());
        assertTrue(result.stateEligible());
    }

    @Test
    void announcementsRetrospectivesAndSetbacksCannotAdvanceState() {
        assertFalse(ImpactScorer.score("ANNOUNCEMENT_ONLY", 8, 4, false, OFFICIAL).stateEligible());
        assertFalse(ImpactScorer.score("RETROSPECTIVE", 8, 4, false, OFFICIAL).stateEligible());
        assertFalse(ImpactScorer.score("SETBACK", 3, 4, false, OFFICIAL).stateEligible());
        assertFalse(ImpactScorer.score("PROGRAM_CANCELLATION", 3, 4, false, OFFICIAL).stateEligible());
    }

    @Test
    void claimedEvidenceCannotChangeState() {
        ScoreResult result = ImpactScorer.score("FLIGHT_TEST", 7, 6, false, CLAIMED);

        assertEquals(2.4, result.impactScore(), 0.0001);
        assertFalse(result.stateEligible());
    }

    @Test
    void rollbackNeedsOfficialEvidenceAndARealDecrease() {
        assertTrue(ImpactScorer.score("ROLLBACK", 4, 6, false, OFFICIAL).stateEligible());
        assertFalse(ImpactScorer.score("ROLLBACK", 4, 6, false, CLAIMED).stateEligible());
        assertFalse(ImpactScorer.score("ROLLBACK", 6, 6, false, OFFICIAL).stateEligible());
    }

    @Test
    void twoLevelJumpRequiresReviewEvenWhenImpactIsBelowEight() {
        ScoreResult result = ImpactScorer.score("PROTOTYPE_DEMO", 6, 4, false, OFFICIAL);

        assertEquals(5.4, result.impactScore(), 0.0001);
        assertTrue(result.requiresReview());
    }
}
