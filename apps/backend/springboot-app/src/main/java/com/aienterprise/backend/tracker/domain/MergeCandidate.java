package com.aienterprise.backend.tracker.domain;

import java.time.LocalDate;

/**
 * A bounded existing-event row considered for semantic merge: its id, actor,
 * occurred-on, and one representative verified evidence quote. Node, event type,
 * and date window are already applied by the query, so the matcher only re-checks
 * actor compatibility and text similarity.
 */
public record MergeCandidate(
        long eventId, String actor, LocalDate occurredOn, String evidenceQuote) {
}
