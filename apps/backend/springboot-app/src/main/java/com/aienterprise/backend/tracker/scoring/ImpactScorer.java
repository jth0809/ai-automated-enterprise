package com.aienterprise.backend.tracker.scoring;

import java.util.Set;

import com.aienterprise.backend.tracker.event.VerificationLevel;

public final class ImpactScorer {

    private static final Set<String> VALID_EVENT_TYPES = Set.of(
            "THEORY_PAPER", "LAB_RESULT", "PROTOTYPE_DEMO", "FLIGHT_TEST",
            "OPERATIONAL_DEPLOYMENT", "COMMERCIALIZATION", "INSTITUTIONAL_ADVANCE",
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY", "RETROSPECTIVE", "ROLLBACK");

    private static final Set<String> NON_STATE_EVENTS = Set.of(
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY", "RETROSPECTIVE");

    private ImpactScorer() {
    }

    public static ScoreResult score(
            String eventType,
            Integer claimedLevel,
            int currentLevel,
            boolean dormant,
            VerificationLevel verification) {
        if (!VALID_EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unknown event type: " + eventType);
        }
        if (claimedLevel == null) {
            return new ScoreResult(0, 0, false, false);
        }
        if (claimedLevel < 1 || claimedLevel > 9 || currentLevel < 0 || currentLevel > 9) {
            throw new IllegalArgumentException("Maturity levels must be between 0 and 9");
        }

        boolean rollback = "ROLLBACK".equals(eventType);
        boolean changed = rollback
                ? claimedLevel < currentLevel
                : claimedLevel > currentLevel || dormant;
        int novelty = changed && !NON_STATE_EVENTS.contains(eventType) ? 1 : 0;
        double impact = novelty * base(claimedLevel) * verificationWeight(verification);

        boolean evidenceEligible = rollback
                ? verification.atLeast(VerificationLevel.OFFICIAL)
                : verification.atLeast(VerificationLevel.PEER_REVIEWED);
        boolean stateEligible = novelty == 1
                && evidenceEligible
                && !NON_STATE_EVENTS.contains(eventType);

        int levelDelta = Math.abs(claimedLevel - currentLevel);
        boolean requiresReview = stateEligible && (
                impact >= 8.0
                || levelDelta >= 2
                || (!rollback && claimedLevel >= 8));

        return new ScoreResult(impact, novelty, stateEligible, requiresReview);
    }

    private static double base(int level) {
        return switch (level) {
            case 1, 2, 3, 4, 5, 6 -> level;
            case 7 -> 8;
            case 8 -> 9;
            case 9 -> 10;
            default -> throw new IllegalArgumentException("Unsupported maturity level: " + level);
        };
    }

    private static double verificationWeight(VerificationLevel verification) {
        return switch (verification) {
            case CLAIMED -> 0.3;
            case PEER_REVIEWED -> 0.7;
            case OFFICIAL -> 0.9;
            case INDEPENDENT -> 1.0;
        };
    }
}
