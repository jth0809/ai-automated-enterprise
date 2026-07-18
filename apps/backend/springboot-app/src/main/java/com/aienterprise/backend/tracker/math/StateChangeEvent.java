package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.util.Objects;

public record StateChangeEvent(
        long eventId,
        int pillar,
        LocalDate occurredOn,
        String eventType,
        int previousLevel,
        int newLevel,
        String previousStatus,
        String newStatus) {

    public StateChangeEvent {
        if (eventId <= 0 || pillar < 1 || pillar > 6) {
            throw new IllegalArgumentException("invalid state-change identity");
        }
        occurredOn = Objects.requireNonNull(occurredOn, "occurredOn");
        eventType = Objects.requireNonNull(eventType, "eventType");
        previousStatus = Objects.requireNonNull(previousStatus, "previousStatus");
        newStatus = Objects.requireNonNull(newStatus, "newStatus");
    }

    public boolean rollback() {
        return "ROLLBACK".equals(eventType);
    }
}
