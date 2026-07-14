package com.aienterprise.backend.tracker.backfill;

public record NodeAuditApproval(
        String reviewerId,
        String focus,
        String decision) {
}
