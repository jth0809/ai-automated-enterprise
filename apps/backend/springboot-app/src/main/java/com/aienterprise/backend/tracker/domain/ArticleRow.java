package com.aienterprise.backend.tracker.domain;

import java.time.Instant;

public record ArticleRow(
        long id,
        long sourceId,
        String url,
        String urlHash,
        String title,
        Instant publishedAt,
        Instant fetchedAt,
        String body,
        boolean bodyExtracted,
        String pipelineStatus,
        int failCount) {
}
