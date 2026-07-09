package com.aienterprise.backend.news;

/**
 * Seam for AI summarization. The default {@link DisabledSummarizer} is a
 * no-op so the news feature runs today without an ANTHROPIC_API_KEY; a
 * Claude-backed implementation is swapped in (via configuration) once the
 * key is provisioned — no call site changes.
 */
@FunctionalInterface
public interface ArticleSummarizer {
    Article summarize(Article article);
}
