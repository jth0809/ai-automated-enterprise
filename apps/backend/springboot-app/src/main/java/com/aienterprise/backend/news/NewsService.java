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

    /**
     * Cap on summarizer (Claude API) calls per ingest run. A feed's first
     * fetch carries its entire history, and summarizing every backlog item
     * bursts straight into Anthropic's 429 rate limit — wasting spend on
     * articles nobody scrolls to. Feeds deliver newest-first, so the budget
     * goes to the freshest items; the rest keep their source excerpt.
     */
    static final int MAX_SUMMARIES_PER_INGEST = 3;

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
            int summarized = 0;
            for (Article article : parsed) {
                if (article.link() != null && !byLink.containsKey(article.link())) {
                    if (summarized < MAX_SUMMARIES_PER_INGEST) {
                        byLink.put(article.link(), summarizer.summarize(article));
                        summarized++;
                    } else {
                        byLink.put(article.link(), article);
                    }
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
