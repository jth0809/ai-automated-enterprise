package com.aienterprise.backend.tracker.domain;

import java.time.Instant;

/**
 * One successful second-context fluke evaluation per review: the verdict,
 * the code-verified evidence quote, and the full version stamp (model,
 * prompt hash, rubric) required by the audit invariant.
 */
public record FlukeEvaluationRow(
        long id,
        long reviewId,
        long eventId,
        String verdict,
        String evidenceQuote,
        boolean quoteVerified,
        String rawOutput,
        String modelId,
        String promptSha256,
        long rubricVersionId,
        Instant createdAt) {
}
