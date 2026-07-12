package com.aienterprise.backend.tracker.domain;

import java.time.LocalDate;

public record EventRow(
        long id,
        String naturalKey,
        long nodeId,
        String eventType,
        Integer claimedLevel,
        String actor,
        LocalDate occurredOn,
        String verificationLevel,
        String eventStatus,
        LocalDate provisionalExpiresOn,
        Double impactScore,
        Integer novelty,
        boolean stateAdvanced,
        long rubricVersionId) {

    public static EventRow draft(
            long nodeId,
            String eventType,
            Integer claimedLevel,
            String actor,
            LocalDate occurredOn,
            String verificationLevel,
            LocalDate provisionalExpiresOn,
            long rubricVersionId) {
        return new EventRow(
                0, null, nodeId, eventType, claimedLevel, actor, occurredOn,
                verificationLevel, "PROVISIONAL", provisionalExpiresOn,
                null, null, false, rubricVersionId);
    }
}
