package com.aienterprise.backend.news;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;

/**
 * Wires the news beans. Defaults are safe-to-deploy: the summarizer stays
 * disabled until {@code anthropic.api-key} (env ANTHROPIC_API_KEY) is set,
 * and the feed list is empty (no ingestion, no egress needed) until
 * {@code news.feeds} is configured as comma-separated {@code source|url}
 * pairs or bare feed URLs (the source label then defaults to the URL's host).
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
    public ArticleSummarizer articleSummarizer(
            RestClient.Builder restClientBuilder,
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.model:claude-opus-4-8}") String model) {
        return summarizer(restClientBuilder, apiKey, model);
    }

    /** Claude headline translator once a key is provisioned; no-op otherwise. */
    static TitleTranslator titleTranslator(RestClient.Builder builder, String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            return new DisabledTitleTranslator();
        }
        return new AnthropicTitleTranslator(builder, apiKey, model);
    }

    /** Claude summarizer once a key is provisioned; no-op fallback otherwise. */
    static ArticleSummarizer summarizer(RestClient.Builder builder, String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("anthropic.api-key not set; AI summarization disabled");
            return new DisabledSummarizer();
        }
        log.info("AI summarization enabled (model {})", model);
        return new AnthropicSummarizer(builder, apiKey, model);
    }

    @Bean
    public TitleTranslator articleTitleTranslator(
            RestClient.Builder restClientBuilder,
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.model:claude-opus-4-8}") String model) {
        return titleTranslator(restClientBuilder, apiKey, model);
    }

    @Bean
    public NewsService newsService(
            RssParser parser, ArticleSummarizer summarizer, TitleTranslator translator) {
        return new NewsService(parser, summarizer, translator);
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

    static List<FeedSpec> parseFeeds(String csv) {
        List<FeedSpec> specs = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return specs;
        }
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            int bar = trimmed.indexOf('|');
            if (bar > 0 && bar < trimmed.length() - 1) {
                specs.add(new FeedSpec(trimmed.substring(0, bar).trim(), trimmed.substring(bar + 1).trim()));
                continue;
            }
            FeedSpec bare = bareUrlSpec(trimmed);
            if (bare != null) {
                specs.add(bare);
            } else {
                log.warn("ignoring malformed feed spec (expected 'source|url' or a feed URL): {}", trimmed);
            }
        }
        return specs;
    }

    /** A bare http(s) URL is a valid spec; its host doubles as the source label. */
    private static FeedSpec bareUrlSpec(String entry) {
        try {
            URI uri = new URI(entry);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && ("http".equals(scheme) || "https".equals(scheme))) {
                return new FeedSpec(uri.getHost(), entry);
            }
        } catch (Exception ignored) {
            // fall through to null: not a URL
        }
        return null;
    }
}
