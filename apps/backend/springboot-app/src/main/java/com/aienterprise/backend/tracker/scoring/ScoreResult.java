package com.aienterprise.backend.tracker.scoring;

public record ScoreResult(
        double impactScore,
        int novelty,
        boolean stateEligible,
        boolean requiresReview) {
}
