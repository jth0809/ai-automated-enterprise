package com.aienterprise.backend.news;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsServiceTest {

    private static String feed(String... links) {
        StringBuilder sb = new StringBuilder("<rss version=\"2.0\"><channel><title>F</title>");
        for (String link : links) {
            sb.append("<item><title>T ").append(link).append("</title><link>")
                    .append(link).append("</link></item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    /** Summarizer that records that it ran by stamping a summary. */
    private static final ArticleSummarizer STAMPING = a -> a.withSummary("SUMMARY:" + a.link());

    @Test
    void ingestStoresParsedArticles() {
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer());

        svc.ingest(feed("https://a", "https://b"), "F");

        List<Article> latest = svc.latest(10);
        assertEquals(2, latest.size());
        assertTrue(latest.stream().anyMatch(x -> x.link().equals("https://a")));
    }

    @Test
    void ingestDedupesByLinkAcrossFeeds() {
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer());

        svc.ingest(feed("https://a", "https://b"), "F");
        svc.ingest(feed("https://b", "https://c"), "F"); // b overlaps

        assertEquals(3, svc.latest(10).size());
    }

    @Test
    void ingestRunsNewArticlesWithinTheBudgetThroughTheSummarizer() {
        NewsService svc = new NewsService(new RssParser(), STAMPING);

        svc.ingest(feed("https://a"), "F");

        assertEquals("SUMMARY:https://a", svc.latest(10).get(0).summary());
    }

    /** Summarizer that counts its calls on top of stamping a summary. */
    private static ArticleSummarizer counting(AtomicInteger calls) {
        return a -> {
            calls.incrementAndGet();
            return a.withSummary("SUMMARY:" + a.link());
        };
    }

    @Test
    void ingestSummarizesAtMostThreeNewArticlesPerCall() {
        // A feed's first fetch carries its whole history; summarizing every
        // item bursts straight into the Anthropic 429 rate limit. Only the
        // first MAX_SUMMARIES_PER_INGEST (newest-first) may hit the API.
        AtomicInteger calls = new AtomicInteger();
        NewsService svc = new NewsService(new RssParser(), counting(calls));

        svc.ingest(feed("https://a", "https://b", "https://c", "https://d", "https://e"), "F");

        assertEquals(3, calls.get());
        List<Article> latest = svc.latest(10);
        assertEquals(5, latest.size());
        // The first three (newest) carry summaries…
        assertEquals("SUMMARY:https://a", latest.get(0).summary());
        assertEquals("SUMMARY:https://b", latest.get(1).summary());
        assertEquals("SUMMARY:https://c", latest.get(2).summary());
        // …the rest are stored untouched, bypassing the summarizer.
        assertNull(latest.get(3).summary());
        assertNull(latest.get(4).summary());
    }

    @Test
    void summaryBudgetResetsForEachIngestCall() {
        // The cap is per feed refresh, not global: the next ingest run gets
        // its own budget of three.
        AtomicInteger calls = new AtomicInteger();
        NewsService svc = new NewsService(new RssParser(), counting(calls));

        svc.ingest(feed("https://a", "https://b", "https://c", "https://d"), "F");
        svc.ingest(feed("https://e", "https://f", "https://g", "https://h"), "G");

        assertEquals(6, calls.get());
    }

    @Test
    void alreadyStoredArticlesDoNotConsumeTheSummaryBudget() {
        AtomicInteger calls = new AtomicInteger();
        NewsService svc = new NewsService(new RssParser(), counting(calls));

        svc.ingest(feed("https://a", "https://b", "https://c"), "F");
        // a-c are dedup hits now; the fresh d-f must still get all 3 slots.
        svc.ingest(feed("https://a", "https://b", "https://c", "https://d", "https://e", "https://f"), "F");

        assertEquals(6, calls.get());
        assertEquals("SUMMARY:https://f",
                svc.latest(10).stream().filter(x -> x.link().equals("https://f"))
                        .findFirst().orElseThrow().summary());
    }

    @Test
    void latestCapsTheReturnedCount() {
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer());
        svc.ingest(feed("https://a", "https://b", "https://c"), "F");

        assertEquals(2, svc.latest(2).size());
    }

    @Test
    void disabledSummarizerLeavesTheArticleUnsummarized() {
        Article a = new Article("t", "l", "s", null, "x", null);
        assertEquals(a, new DisabledSummarizer().summarize(a));
    }
}
