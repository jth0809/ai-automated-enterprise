package com.aienterprise.backend.tracker.domain;

import java.time.Instant;
import java.util.List;

public record FeedPublicationWindow(
        long sourceId,
        String sourceCode,
        List<Instant> publicationTimes) {

    public FeedPublicationWindow {
        if (sourceId <= 0 || sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("feed source identity is required");
        }
        publicationTimes = List.copyOf(publicationTimes);
    }
}
