package com.aienterprise.backend.news;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically fetches each configured feed and ingests it. Resilient by
 * design (backend AGENTS.md): one failing feed is logged and skipped so it
 * never aborts the whole cycle. With no feeds configured, {@link #refresh()}
 * is a no-op, so the app is safe to deploy before feeds/egress are set up.
 */
public class NewsIngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsIngestionScheduler.class);

    /** One feed source: a human-readable name and its URL. */
    public record FeedSpec(String source, String url) {
    }

    private final NewsService news;
    private final FeedFetcher fetcher;
    private final List<FeedSpec> feeds;

    public NewsIngestionScheduler(NewsService news, FeedFetcher fetcher, List<FeedSpec> feeds) {
        this.news = news;
        this.fetcher = fetcher;
        this.feeds = feeds;
    }

    @Scheduled(cron = "${news.refresh-cron:0 0 * * * *}")
    public void refresh() {
        for (FeedSpec feed : feeds) {
            try {
                news.ingest(fetcher.fetch(feed.url()), feed.source());
            } catch (Exception e) {
                log.warn("feed ingestion failed for {} ({}): {}", feed.source(), feed.url(), e.toString());
            }
        }
    }
}
