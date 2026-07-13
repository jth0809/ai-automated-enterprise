package com.aienterprise.backend.tracker.backfill;

import java.time.LocalDate;
import java.util.List;

public record HistoricalCandidate(
        String candidateId,
        String eventTitle,
        List<String> candidateTopics,
        String actor,
        LocalDate occurredOn,
        List<HistoricalEvidenceReference> evidence,
        String discoveryStatus,
        String discoveryNote) {

    public HistoricalCandidate {
        candidateTopics = List.copyOf(candidateTopics);
        evidence = List.copyOf(evidence);
    }
}
