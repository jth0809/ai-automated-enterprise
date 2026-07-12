package com.aienterprise.backend.tracker.ingest;

/**
 * Result of generic article extraction: a normalized title and plain text
 * whose paragraphs stay separated by blank lines. No markup survives.
 */
public record ExtractedArticle(String title, String text) {
}
