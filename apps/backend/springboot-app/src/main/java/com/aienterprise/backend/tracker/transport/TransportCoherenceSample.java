package com.aienterprise.backend.tracker.transport;

import java.time.Instant;

/** Read-only event reference selected for human coherence audit. */
public record TransportCoherenceSample(
        long id,
        long reportId,
        long eventId,
        String status,
        String reviewerNote,
        Instant reviewedAt) {
}
