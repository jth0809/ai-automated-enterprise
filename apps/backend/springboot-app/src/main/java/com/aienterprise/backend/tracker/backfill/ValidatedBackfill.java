package com.aienterprise.backend.tracker.backfill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ValidatedBackfill(
        List<BackfillClaim> claims,
        Map<String, HistoricalCandidate> candidates,
        List<String> errors,
        int candidateRecordCount) {

    public ValidatedBackfill {
        if (candidateRecordCount < 0
                || candidateRecordCount < candidates.size()) {
            throw new IllegalArgumentException(
                    "candidate corpus count cannot be smaller than used candidates");
        }
        claims = List.copyOf(claims);
        candidates = Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
        errors = List.copyOf(errors);
    }

    /** Compatibility constructor for callers that only materialize used candidates. */
    public ValidatedBackfill(
            List<BackfillClaim> claims,
            Map<String, HistoricalCandidate> candidates,
            List<String> errors) {
        this(claims, candidates, errors, candidates.size());
    }
}
