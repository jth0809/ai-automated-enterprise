package com.aienterprise.backend.tracker.domain;

import java.time.LocalDate;

public record ClassificationRow(
        long id,
        long articleId,
        Long eventId,
        String nodeCode,
        String eventType,
        Integer claimedLevel,
        String actor,
        LocalDate occurredOn,
        String publicationPath,
        String evidenceQuote,
        boolean quoteVerified,
        String rawOutput,
        long rubricVersionId) {
}
