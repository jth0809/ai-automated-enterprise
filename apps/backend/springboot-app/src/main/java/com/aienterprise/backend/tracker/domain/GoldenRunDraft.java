package com.aienterprise.backend.tracker.domain;

public record GoldenRunDraft(
        String mode,
        String datasetVersion,
        String promptVersion,
        String modelVersion,
        long rubricVersionId,
        String expectedSchemaVersion,
        int totalCount) {
}
