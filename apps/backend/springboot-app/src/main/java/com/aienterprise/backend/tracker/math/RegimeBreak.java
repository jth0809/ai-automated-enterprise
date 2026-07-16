package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.util.Objects;

public record RegimeBreak(
        long id,
        int pillar,
        LocalDate breakDate,
        long causeEventId,
        String paramsVersion) {

    public RegimeBreak {
        if (id <= 0 || causeEventId <= 0 || pillar < 1 || pillar > 6) {
            throw new IllegalArgumentException("invalid regime-break identity");
        }
        breakDate = Objects.requireNonNull(breakDate, "breakDate");
        paramsVersion = Objects.requireNonNull(paramsVersion, "paramsVersion");
    }
}
