package com.aienterprise.backend.tracker.backfill;

import java.util.List;

public record ValidatedNodeAudit(
        List<NodeAuditEntry> entries,
        List<String> errors) {

    public ValidatedNodeAudit {
        entries = List.copyOf(entries);
        errors = List.copyOf(errors);
    }
}
