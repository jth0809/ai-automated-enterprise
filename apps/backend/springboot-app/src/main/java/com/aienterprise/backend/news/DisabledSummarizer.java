package com.aienterprise.backend.news;

/**
 * No-op summarizer used until an ANTHROPIC_API_KEY is provisioned. Returns
 * the article unchanged (its {@code summary} stays null; the UI falls back
 * to the source excerpt).
 */
public class DisabledSummarizer implements ArticleSummarizer {

    @Override
    public Article summarize(Article article) {
        return article;
    }
}
