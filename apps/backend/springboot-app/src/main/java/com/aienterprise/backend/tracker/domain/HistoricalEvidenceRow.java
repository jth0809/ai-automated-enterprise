package com.aienterprise.backend.tracker.domain;

import java.time.Instant;
import java.time.LocalDate;

public record HistoricalEvidenceRow(
        long id,
        String backfillId,
        String candidateId,
        String occurredOnPrecision,
        long eventId,
        long sourceId,
        String url,
        String locator,
        LocalDate accessedOn,
        String contentSha256,
        String publicationPath,
        String factSummary,
        String factReviewStatus,
        String rubricReviewStatus,
        String referenceStatus,
        String reviewerNote,
        Instant createdAt) {

    public static HistoricalEvidenceRow draft(
            String backfillId,
            String candidateId,
            String occurredOnPrecision,
            long eventId,
            long sourceId,
            String url,
            String locator,
            LocalDate accessedOn,
            String contentSha256,
            String publicationPath,
            String factSummary,
            String reviewerNote) {
        return new HistoricalEvidenceRow(
                0, backfillId, candidateId, occurredOnPrecision, eventId, sourceId, url, locator,
                accessedOn, contentSha256, publicationPath, factSummary,
                "APPROVED", "APPROVED", "APPROVED", reviewerNote, null);
    }
}
