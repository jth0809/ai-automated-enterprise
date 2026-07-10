package com.aienterprise.backend.news;

/**
 * A news item. {@code link} is the natural key (used for dedup).
 * {@code excerpt} is the source's own description; {@code summary} is the
 * AI-generated summary and {@code translatedTitle} the AI-translated (Korean)
 * headline — each left null until the corresponding AI step runs for the
 * article (summaries go to the first few per ingest, translations to the
 * rest).
 */
public record Article(
        String title,
        String link,
        String source,
        String publishedAt,
        String excerpt,
        String summary,
        String translatedTitle) {

    /** Returns a copy carrying the given AI summary. */
    public Article withSummary(String newSummary) {
        return new Article(title, link, source, publishedAt, excerpt, newSummary, translatedTitle);
    }

    /** Returns a copy carrying the given translated headline. */
    public Article withTranslatedTitle(String newTranslatedTitle) {
        return new Article(title, link, source, publishedAt, excerpt, summary, newTranslatedTitle);
    }
}
