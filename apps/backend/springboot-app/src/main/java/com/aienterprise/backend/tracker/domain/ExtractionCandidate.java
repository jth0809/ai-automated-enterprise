package com.aienterprise.backend.tracker.domain;

import java.util.Set;

/**
 * One PENDING article queued for full-text extraction, together with the
 * exact lowercase hosts the fetcher may contact for its source (BODY and
 * BOTH purpose rows of source_domain).
 */
public record ExtractionCandidate(long id, String url, Set<String> allowedHosts) {
}
