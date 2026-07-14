package com.aienterprise.backend.tracker.backfill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ValidatedBackfill(
        List<BackfillClaim> claims,
        Map<String, HistoricalCandidate> candidates,
        List<String> errors) {

    public ValidatedBackfill {
        claims = List.copyOf(claims);
        candidates = Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
        errors = List.copyOf(errors);
    }
}
