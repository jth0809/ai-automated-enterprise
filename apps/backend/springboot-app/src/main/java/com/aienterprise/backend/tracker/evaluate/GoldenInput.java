package com.aienterprise.backend.tracker.evaluate;

public record GoldenInput(
        long itemId,
        String caseCode,
        String fixtureKind,
        String title,
        String body,
        String expectedSchemaVersion) {
}
