package com.aienterprise.backend.news;

/**
 * A news item. {@code link} is the natural key (used for dedup).
 * {@code excerpt} is the source's own description; {@code summary} is the
 * AI-generated summary, left null until the summarizer is enabled (once an
 * ANTHROPIC_API_KEY is provisioned).
 */
public record Article(
        String title,
        String link,
        String source,
        String publishedAt,
        String excerpt,
        String summary) {

    /** Returns a copy carrying the given AI summary. */
    public Article withSummary(String newSummary) {
        return new Article(title, link, source, publishedAt, excerpt, newSummary);
    }
}
