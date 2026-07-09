package com.aienterprise.backend.news;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory news store for P1: ingest parses a feed, runs each new article
 * through the {@link ArticleSummarizer}, and stores it deduplicated by link.
 * Persistence to ATP (and Redis-backed dedup) is a later step — the store is
 * intentionally kept behind this class so that swap is local. Feeds deliver
 * newest-first, so {@code latest} preserves that order.
 */
public class NewsService {

    private final RssParser parser;
    private final ArticleSummarizer summarizer;
    private final Map<String, Article> byLink = new LinkedHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public NewsService(RssParser parser, ArticleSummarizer summarizer) {
        this.parser = parser;
        this.summarizer = summarizer;
    }

    public void ingest(String feedXml, String source) {
        List<Article> parsed = parser.parse(feedXml, source);
        lock.writeLock().lock();
        try {
            for (Article article : parsed) {
                if (article.link() != null && !byLink.containsKey(article.link())) {
                    byLink.put(article.link(), summarizer.summarize(article));
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Article> latest(int limit) {
        lock.readLock().lock();
        try {
            List<Article> all = new ArrayList<>(byLink.values());
            return all.subList(0, Math.min(limit, all.size()));
        } finally {
            lock.readLock().unlock();
        }
    }
}
