package com.aienterprise.backend.tracker.event;

public record SourceEvidence(long sourceId, int tier, String sourceType, String publicationPath) {
}
