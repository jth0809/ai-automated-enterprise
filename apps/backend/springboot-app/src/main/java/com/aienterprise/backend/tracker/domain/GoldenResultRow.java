package com.aienterprise.backend.tracker.domain;

public record GoldenResultRow(
        long runId,
        long itemId,
        String actualOutputSha256,
        boolean matched,
        String mismatchFields,
        String errorCode) {
}
