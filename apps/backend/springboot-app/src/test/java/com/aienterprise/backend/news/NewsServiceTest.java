package com.aienterprise.backend.news;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void ingestRunsEachArticleThroughTheSummarizer() {
        NewsService svc = new NewsService(new RssParser(), STAMPING);

        svc.ingest(feed("https://a"), "F");

        assertEquals("SUMMARY:https://a", svc.latest(10).get(0).summary());
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
