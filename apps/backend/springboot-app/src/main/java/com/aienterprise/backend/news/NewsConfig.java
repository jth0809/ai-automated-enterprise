package com.aienterprise.backend.news;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;

/**
 * Wires the news beans. Defaults are safe-to-deploy: the summarizer is
 * disabled (no ANTHROPIC_API_KEY needed) and the feed list is empty (no
 * ingestion, no egress needed) until {@code news.feeds} is configured as
 * comma-separated {@code source|url} pairs.
 */
@Configuration
@EnableScheduling
public class NewsConfig {

    private static final Logger log = LoggerFactory.getLogger(NewsConfig.class);

    @Bean
    public RssParser rssParser() {
        return new RssParser();
    }

    @Bean
    public ArticleSummarizer articleSummarizer() {
        return new DisabledSummarizer();
    }

    @Bean
    public NewsService newsService(RssParser parser, ArticleSummarizer summarizer) {
        return new NewsService(parser, summarizer);
    }

    @Bean
    public FeedFetcher feedFetcher() {
        return new HttpFeedFetcher();
    }

    @Bean
    public Notifier notifier() {
        return new DisabledNotifier();
    }

    @Bean
    public NewsIngestionScheduler newsIngestionScheduler(
            NewsService news, FeedFetcher fetcher, @Value("${news.feeds:}") String feeds) {
        return new NewsIngestionScheduler(news, fetcher, parseFeeds(feeds));
    }

    private static List<FeedSpec> parseFeeds(String csv) {
        List<FeedSpec> specs = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return specs;
        }
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            int bar = trimmed.indexOf('|');
            if (bar <= 0 || bar == trimmed.length() - 1) {
                log.warn("ignoring malformed feed spec (expected 'source|url'): {}", trimmed);
                continue;
            }
            specs.add(new FeedSpec(trimmed.substring(0, bar).trim(), trimmed.substring(bar + 1).trim()));
        }
        return specs;
    }
}
