package com.aienterprise.backend.tracker.backfill;

import java.util.List;

public record CorpusReport(
        int totalCount,
        int readyCount,
        int rejectedCount,
        List<String> errors) {

    public CorpusReport {
        errors = List.copyOf(errors);
    }
}
