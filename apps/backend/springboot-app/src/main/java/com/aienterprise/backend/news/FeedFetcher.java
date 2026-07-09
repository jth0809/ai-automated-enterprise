package com.aienterprise.backend.news;

/** Seam for fetching a feed URL — an interface so ingestion is testable without network. */
@FunctionalInterface
public interface FeedFetcher {
    String fetch(String url) throws Exception;
}
