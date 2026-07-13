package com.aienterprise.backend.tracker.backfill;

import java.time.LocalDate;
import java.util.List;

public record BackfillClaim(
        String backfillId,
        String candidateId,
        String nodeSetVersion,
        String rubricVersion,
        String nodeCode,
        String eventType,
        Integer claimedLevel,
        String actor,
        LocalDate occurredOn,
        String occurredOnPrecision,
        String expectedVerificationLevel,
        String eventTitle,
        String rubricJustification,
        List<String> evidenceRefs,
        BackfillReview review) {

    public BackfillClaim {
        evidenceRefs = List.copyOf(evidenceRefs);
    }
}
