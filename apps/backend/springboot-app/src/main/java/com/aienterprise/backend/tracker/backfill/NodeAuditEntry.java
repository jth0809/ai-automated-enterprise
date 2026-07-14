package com.aienterprise.backend.tracker.backfill;

import java.time.LocalDate;
import java.util.List;

public record NodeAuditEntry(
        String nodeCode,
        String nodeSetVersion,
        String rubricVersion,
        LocalDate auditedOn,
        int auditedLevel,
        String status,
        List<String> levelClaimIds,
        List<String> levelEvidenceRefs,
        List<String> statusClaimIds,
        String nextLevelGap,
        String statusRationale,
        List<NodeAuditApproval> reviews) {

    public NodeAuditEntry {
        levelClaimIds = List.copyOf(levelClaimIds);
        levelEvidenceRefs = List.copyOf(levelEvidenceRefs);
        statusClaimIds = List.copyOf(statusClaimIds);
        reviews = List.copyOf(reviews);
    }
}
