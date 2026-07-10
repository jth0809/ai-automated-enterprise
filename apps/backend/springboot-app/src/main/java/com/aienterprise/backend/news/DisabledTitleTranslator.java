package com.aienterprise.backend.news;

import java.util.List;

/**
 * No-op translator used until an ANTHROPIC_API_KEY is provisioned. Returns
 * the articles unchanged ({@code translatedTitle} stays null; the UI falls
 * back to the original headline).
 */
public class DisabledTitleTranslator implements TitleTranslator {

    @Override
    public List<Article> translateTitles(List<Article> articles) {
        return articles;
    }
}
