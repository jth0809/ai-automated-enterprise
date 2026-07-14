package com.aienterprise.backend.tracker.domain;

public record GoldenSetItemRow(
        long id,
        String caseCode,
        String fixtureKind,
        String title,
        String body,
        String expectedOutput,
        String expectedSchemaVersion,
        String datasetVersion,
        String inputSha256) {
}
