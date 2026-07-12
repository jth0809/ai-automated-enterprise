package com.aienterprise.backend.tracker.domain;

import java.time.Instant;

public record ReviewRow(
        long id,
        long eventId,
        String reason,
        String flukeResult,
        String status,
        String reviewerNote,
        Instant createdAt,
        Instant resolvedAt) {
}
