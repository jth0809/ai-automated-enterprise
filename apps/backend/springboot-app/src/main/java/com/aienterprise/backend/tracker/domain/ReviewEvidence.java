package com.aienterprise.backend.tracker.domain;

import java.time.LocalDate;

public record ReviewEvidence(
        EvidenceKind kind,
        String sourceLabel,
        String url,
        String evidenceQuote,
        String factSummary,
        String locator,
        LocalDate accessedOn) {

    // One-release Java compatibility for callers that used the original names.
    public String articleTitle() {
        return sourceLabel;
    }

    public String articleUrl() {
        return url;
    }
}
