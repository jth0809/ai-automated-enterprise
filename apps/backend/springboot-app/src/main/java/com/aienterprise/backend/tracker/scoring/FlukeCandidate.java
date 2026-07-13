package com.aienterprise.backend.tracker.scoring;

import java.time.LocalDate;
import java.util.List;

/**
 * Independent-evaluation input for the fluke filter: the registered node
 * definition, the exact candidate claim, the current node state, and the
 * verified article bodies. Deliberately excludes the first classifier's
 * reasoning and is not the admin API DTO.
 */
public record FlukeCandidate(
        long reviewId,
        long eventId,
        String nodeCode,
        String nodeDefinition,
        String scaleType,
        int currentLevel,
        String eventType,
        Integer claimedLevel,
        String actor,
        LocalDate occurredOn,
        List<String> articleBodies) {
}
