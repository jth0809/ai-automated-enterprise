package com.aienterprise.backend.news;

import java.util.List;

/**
 * Seam for batch headline translation. New articles that have no summary
 * still get a readable Korean headline through ONE bounded batch API call —
 * never one call per article (resource-constraint principle).
 * The default {@link DisabledTitleTranslator} is a no-op so the feature is
 * safe to deploy without an ANTHROPIC_API_KEY.
 */
@FunctionalInterface
public interface TitleTranslator {

    /** Returns copies carrying {@code translatedTitle}; input order kept. */
    List<Article> translateTitles(List<Article> articles);
}
