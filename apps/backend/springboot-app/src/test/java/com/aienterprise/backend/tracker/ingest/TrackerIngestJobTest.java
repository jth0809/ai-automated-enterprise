package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.news.FeedFetcher;
import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;
import com.aienterprise.backend.news.RssParser;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TrackerIngestJobTest {

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void ingestingTheSameFeedsTwiceDoesNotDuplicateArticles() {
        List<FeedSpec> feeds = List.of(
                new FeedSpec("NASA", "https://www.nasa.gov/feed"),
                new FeedSpec("SPACENEWS", "https://spacenews.com/feed"));
        Map<String, String> payloads = Map.of(
                feeds.get(0).url(), rss("NASA milestone", "https://nasa.test/a"),
                feeds.get(1).url(), rss("SpaceNews milestone", "https://spacenews.test/b"));
        FeedFetcher fetcher = payloads::get;
        TrackerIngestJob job = new TrackerIngestJob(fetcher, new RssParser(), repository, feeds);

        job.runOnce();
        job.runOnce();

        assertEquals(2, articleCount());
    }

    @Test
    void oneBrokenFeedDoesNotAbortRemainingFeeds() {
        List<FeedSpec> feeds = List.of(
                new FeedSpec("NASA", "https://www.nasa.gov/broken"),
                new FeedSpec("SPACENEWS", "https://spacenews.com/good"));
        FeedFetcher fetcher = url -> {
            if (url.contains("broken")) {
                throw new IllegalStateException("simulated failure");
            }
            return rss("Good article", "https://spacenews.test/good");
        };
        TrackerIngestJob job = new TrackerIngestJob(fetcher, new RssParser(), repository, feeds);

        job.runOnce();

        assertEquals(1, articleCount());
    }

    @Test
    void atomEntriesAreIngestedWithIsoPublishedTime() {
        List<FeedSpec> feeds = List.of(new FeedSpec("NASA", "https://www.nasa.gov/atom"));
        FeedFetcher fetcher = url -> """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><title>Transfer demo</title>
                    <link rel="alternate" href="https://nasa.test/atom-a"/>
                    <published>2026-07-12T01:02:03Z</published>
                    <summary>Propellant moved between tanks.</summary>
                  </entry>
                </feed>
                """;
        TrackerIngestJob job = new TrackerIngestJob(fetcher, new RssParser(), repository, feeds);

        job.runOnce();

        assertEquals(1, articleCount());
        assertEquals(java.time.Instant.parse("2026-07-12T01:02:03Z"),
                jdbc.sql("SELECT published_at FROM article WHERE url = 'https://nasa.test/atom-a'")
                        .query(java.sql.Timestamp.class).single().toInstant());
    }

    @Test
    void oneFailingArticleDoesNotAbortRemainingArticlesInTheFeed() {
        // First item's URL exceeds the article.url column width (1000), so its
        // insert fails at the database; the second item must still land.
        String oversizedLink = "https://nasa.test/" + "x".repeat(1_500);
        List<FeedSpec> feeds = List.of(new FeedSpec("NASA", "https://www.nasa.gov/feed"));
        FeedFetcher fetcher = url -> """
                <rss version="2.0"><channel>
                  <item><title>Broken row</title><link>%s</link></item>
                  <item><title>Good row</title><link>https://nasa.test/good</link></item>
                </channel></rss>
                """.formatted(oversizedLink);
        TrackerIngestJob job = new TrackerIngestJob(fetcher, new RssParser(), repository, feeds);

        job.runOnce();

        assertEquals(1, articleCount());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM article WHERE url = 'https://nasa.test/good'")
                .query(Integer.class).single());
    }

    @Test
    void bodyDomainPolicyDecidesPendingVersusSkippedOnInsert() {
        List<FeedSpec> feeds = List.of(new FeedSpec("NASA", "https://www.nasa.gov/feed"));
        FeedFetcher fetcher = url -> """
                <rss version="2.0"><channel>
                  <item><title>Allowlisted</title><link>https://www.nasa.gov/story-a</link></item>
                  <item><title>Off policy</title><link>https://unknown.example/story-b</link></item>
                </channel></rss>
                """;
        TrackerIngestJob job = new TrackerIngestJob(fetcher, new RssParser(), repository, feeds);

        job.runOnce();

        assertEquals("PENDING", extractionStatus("https://www.nasa.gov/story-a"));
        assertEquals("SKIPPED", extractionStatus("https://unknown.example/story-b"));
    }

    private String extractionStatus(String url) {
        return jdbc.sql("SELECT body_extraction_status FROM article WHERE url = :url")
                .param("url", url).query(String.class).single();
    }

    private int articleCount() {
        return jdbc.sql("SELECT COUNT(*) FROM article").query(Integer.class).single();
    }

    private String rss(String title, String link) {
        return """
                <rss version="2.0"><channel><item>
                  <title>%s</title><link>%s</link>
                  <pubDate>Sun, 12 Jul 2026 12:00:00 GMT</pubDate>
                  <description>Verified test description</description>
                </item></channel></rss>
                """.formatted(title, link);
    }
}
