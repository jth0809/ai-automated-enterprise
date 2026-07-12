package com.aienterprise.backend.tracker.ingest;

/**
 * Pure extraction over fetched bytes. Implementations must stay generic:
 * no source names, hostnames, or site-specific selectors. An unsuitable
 * page (too little content) is rejected with IllegalArgumentException so
 * the caller can fall back to the preserved RSS summary.
 */
public interface ArticleBodyExtractor {

    ExtractedArticle extract(FetchedPage page);
}
