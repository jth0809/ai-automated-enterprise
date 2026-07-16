package com.aienterprise.backend.tracker.governance;

import java.time.LocalDate;

/** Human-reviewed governance fact with reference-only provenance. */
public record GovernanceRecord(
        String recordId,
        String recordType,
        String jurisdiction,
        String subject,
        String status,
        LocalDate effectiveOn,
        String effectiveOnPrecision,
        String sourceCode,
        String sourceUrl,
        LocalDate accessedOn,
        String contentSha256,
        String publicationPath,
        String factSummary,
        String reviewStatus) {
}
