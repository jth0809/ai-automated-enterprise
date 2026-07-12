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
