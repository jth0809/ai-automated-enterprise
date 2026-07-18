package com.aienterprise.backend.tracker.domain;

import java.time.Instant;

public record BackfillImportRow(
        String datasetVersion,
        String datasetSha256,
        String nodeSetVersion,
        long rubricVersionId,
        Instant importedAt,
        int recordCount,
        Integer candidateRecordCount) {

    /** Compatibility constructor for pre-V20 callers and stored imports. */
    public BackfillImportRow(
            String datasetVersion,
            String datasetSha256,
            String nodeSetVersion,
            long rubricVersionId,
            Instant importedAt,
            int recordCount) {
        this(datasetVersion, datasetSha256, nodeSetVersion, rubricVersionId,
                importedAt, recordCount, null);
    }

    public static BackfillImportRow draft(
            String datasetVersion,
            String datasetSha256,
            String nodeSetVersion,
            long rubricVersionId,
            int recordCount,
            int candidateRecordCount) {
        return new BackfillImportRow(
                datasetVersion, datasetSha256, nodeSetVersion,
                rubricVersionId, null, recordCount, candidateRecordCount);
    }
}
