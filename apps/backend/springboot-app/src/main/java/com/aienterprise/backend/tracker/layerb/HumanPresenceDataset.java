package com.aienterprise.backend.tracker.layerb;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Metadata and numeric transitions from one human-reviewed CSV snapshot. */
public record HumanPresenceDataset(
        String datasetVersion,
        String sourceLabel,
        String sourceUrl,
        LocalDate accessedOn,
        Instant completeThroughUtc,
        List<HumanPresenceTransition> transitions) {

    public HumanPresenceDataset {
        transitions = List.copyOf(transitions);
    }
}
