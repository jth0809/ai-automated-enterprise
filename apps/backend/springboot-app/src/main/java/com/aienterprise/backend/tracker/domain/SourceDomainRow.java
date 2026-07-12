package com.aienterprise.backend.tracker.domain;

public record SourceDomainRow(
        long sourceId,
        String sourceCode,
        String domain,
        String purpose) {
}
