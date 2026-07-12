package com.aienterprise.backend.tracker.ingest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.news.Article;
import com.aienterprise.backend.news.FeedFetcher;
import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;
import com.aienterprise.backend.news.RssParser;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class TrackerIngestJob {

    private static final Logger log = LoggerFactory.getLogger(TrackerIngestJob.class);

    private final FeedFetcher fetcher;
    private final RssParser parser;
    private final TrackerRepository repository;
    private final List<FeedSpec> feeds;

    public TrackerIngestJob(
            FeedFetcher fetcher,
            RssParser parser,
            TrackerRepository repository,
            @Qualifier("trackerFeeds") List<FeedSpec> feeds) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.repository = repository;
        this.feeds = List.copyOf(feeds);
    }

    @Scheduled(cron = "${tracker.ingest-cron:0 10 * * * *}")
    @SchedulerLock(name = "tracker-ingest", lockAtLeastFor = "PT1M")
    public void runOnce() {
        for (FeedSpec feed : feeds) {
            try {
                ingestFeed(feed);
            } catch (Exception e) {
                log.warn("tracker feed ingestion failed for {} ({}): {}",
                        feed.source(), feed.url(), e.toString());
            }
        }
    }

    private void ingestFeed(FeedSpec feed) throws Exception {
        String host = URI.create(feed.url()).getHost();
        long sourceId = repository.findSourceIdForFeed(feed.source(), host)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Feed is not registered in source_registry: " + feed.source()));
        for (Article article : parser.parse(fetcher.fetch(feed.url()), feed.source())) {
            if (article.link() == null || article.link().isBlank()) {
                continue;
            }
            try {
                repository.insertArticleIfNew(
                        article.link(),
                        sha256(article.link()),
                        sourceId,
                        article.title(),
                        parsePublishedAt(article.publishedAt()),
                        body(article));
            } catch (RuntimeException e) {
                log.warn("tracker article ingestion failed for {} ({}): {}",
                        feed.source(), article.link(), e.toString());
            }
        }
    }

    private static String body(Article article) {
        String title = article.title() == null ? "" : article.title().trim();
        String excerpt = article.excerpt() == null ? "" : article.excerpt().trim();
        if (title.isEmpty()) {
            return excerpt;
        }
        if (excerpt.isEmpty()) {
            return title;
        }
        return title + "\n\n" + excerpt;
    }

    private static Instant parsePublishedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException notRfc1123) {
            // Fall through to the ISO-8601 forms used by Atom and Dublin Core.
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException notUtcInstant) {
            // Fall through to the offset form (e.g. 2026-07-11T09:30:00+09:00).
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException invalidDate) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
