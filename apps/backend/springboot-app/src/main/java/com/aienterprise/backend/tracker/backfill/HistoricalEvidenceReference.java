package com.aienterprise.backend.tracker.backfill;

import java.net.URI;
import java.time.LocalDate;

public record HistoricalEvidenceReference(
        String sourceCode,
        URI url,
        String locator,
        LocalDate accessedOn,
        String contentSha256,
        String publicationPath,
        String factSummary) {
}
