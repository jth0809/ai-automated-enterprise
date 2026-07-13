package com.aienterprise.backend.tracker.domain;

import java.time.Instant;

public record ReviewRow(
        long id,
        long eventId,
        String reason,
        String flukeResult,
        String status,
        String reviewerNote,
        int priority,
        String flukeStatus,
        int flukeFailCount,
        String flukeLastError,
        Instant createdAt,
        Instant resolvedAt) {
}
