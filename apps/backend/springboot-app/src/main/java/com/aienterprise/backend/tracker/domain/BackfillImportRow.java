package com.aienterprise.backend.tracker.domain;

import java.time.Instant;

public record BackfillImportRow(
        String datasetVersion,
        String datasetSha256,
        String nodeSetVersion,
        long rubricVersionId,
        Instant importedAt,
        int recordCount) {

    public static BackfillImportRow draft(
            String datasetVersion,
            String datasetSha256,
            String nodeSetVersion,
            long rubricVersionId,
            int recordCount) {
        return new BackfillImportRow(
                datasetVersion, datasetSha256, nodeSetVersion,
                rubricVersionId, null, recordCount);
    }
}
