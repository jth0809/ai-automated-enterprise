package com.aienterprise.backend.tracker.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Complete review context for a human decision: the queue row, the candidate
 * event, the node's current state, and the quote-verified evidence records.
 */
public record ReviewCase(
        long reviewId,
        String reason,
        int priority,
        String flukeStatus,
        String flukeResult,
        Instant createdAt,
        long eventId,
        String eventType,
        LocalDate occurredOn,
        String actor,
        String verificationLevel,
        Double impactScore,
        Integer claimedLevel,
        String nodeCode,
        String nodeName,
        String scaleType,
        int currentLevel,
        int sourceCount,
        List<ReviewEvidence> evidence,
        String status,
        String reviewerNote) {
}
