package com.aienterprise.backend.news;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

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

    /** Feed items given as {@code "link|pubDate"} ({@code "link"} = no date). */
    private static String datedFeed(String... linkAndPubDate) {
        StringBuilder sb = new StringBuilder("<rss version=\"2.0\"><channel><title>F</title>");
        for (String entry : linkAndPubDate) {
            int bar = entry.indexOf('|');
            String link = bar < 0 ? entry : entry.substring(0, bar);
            sb.append("<item><title>T ").append(link).append("</title><link>")
                    .append(link).append("</link>");
            if (bar >= 0) {
                sb.append("<pubDate>").append(entry.substring(bar + 1)).append("</pubDate>");
            }
            sb.append("</item>");
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
    void latestReturnsNewestFirstAcrossIngestRuns() {
        // Regression: hourly refreshes append newer articles at the tail of
        // the insertion-ordered store, so latest(20) stayed frozen on the
        // first post-restart batch and the UI never showed anything newer.
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer());
        svc.ingest(datedFeed(
                "https://old-2|Wed, 08 Jul 2026 09:00:00 GMT",
                "https://old-1|Tue, 07 Jul 2026 22:00:00 GMT"), "F");
        // A later refresh delivers a newer article.
        svc.ingest(datedFeed("https://new-1|Fri, 10 Jul 2026 01:00:00 GMT"), "F");

        List<Article> top = svc.latest(2);
        assertEquals("https://new-1", top.get(0).link());
        assertEquals("https://old-2", top.get(1).link());
    }

    @Test
    void latestPutsUndatedOrUnparseableArticlesLast() {
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer());
        svc.ingest(datedFeed(
                "https://undated",
                "https://garbled|not a date",
                "https://dated|Thu, 09 Jul 2026 10:00:00 GMT"), "F");

        List<Article> all = svc.latest(10);
        assertEquals("https://dated", all.get(0).link());
        assertTrue(all.subList(1, 3).stream()
                .allMatch(a -> a.link().equals("https://undated") || a.link().equals("https://garbled")));
    }

    @Test
    void latestCapsTheReturnedCount() {
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer());
        svc.ingest(feed("https://a", "https://b", "https://c"), "F");

        assertEquals(2, svc.latest(2).size());
    }

    @Test
    void disabledSummarizerLeavesTheArticleUnsummarized() {
        Article a = new Article("t", "l", "s", null, "x", null, null);
        assertEquals(a, new DisabledSummarizer().summarize(a));
    }

    @Test
    void disabledTranslatorLeavesArticlesUntranslated() {
        Article a = new Article("t", "l", "s", null, "x", null, null);
        assertEquals(List.of(a), new DisabledTitleTranslator().translateTitles(List.of(a)));
    }

    @Test
    void articlesBeyondTheSummaryBudgetGetBatchTranslatedTitles() {
        // The summary budget covers the newest 3; everything else must still
        // become readable through ONE batched translation call — never one
        // call per article.
        AtomicInteger batches = new AtomicInteger();
        List<Article> received = new ArrayList<>();
        TitleTranslator translator = articles -> {
            batches.incrementAndGet();
            received.addAll(articles);
            return articles.stream().map(a -> a.withTranslatedTitle("KO:" + a.link())).toList();
        };
        NewsService svc = new NewsService(new RssParser(), STAMPING, translator);

        svc.ingest(feed("https://a", "https://b", "https://c", "https://d", "https://e"), "F");

        assertEquals(1, batches.get());
        assertEquals(List.of("https://d", "https://e"),
                received.stream().map(Article::link).toList());

        List<Article> all = svc.latest(10);
        assertEquals(3, all.stream().filter(x -> x.summary() != null).count());
        assertEquals("KO:https://d", byLink(all, "https://d").translatedTitle());
        assertEquals("KO:https://e", byLink(all, "https://e").translatedTitle());
        // Summarized articles are not translated on top.
        assertEquals(0, all.stream()
                .filter(x -> x.summary() != null && x.translatedTitle() != null).count());
    }

    @Test
    void translatorIsNotCalledWhenEverythingFitsTheSummaryBudget() {
        AtomicInteger batches = new AtomicInteger();
        TitleTranslator translator = articles -> {
            batches.incrementAndGet();
            return articles;
        };
        NewsService svc = new NewsService(new RssParser(), STAMPING, translator);

        svc.ingest(feed("https://a", "https://b"), "F");

        assertEquals(0, batches.get());
    }

    @Test
    void summaryFailuresFallBackToOneTitleTranslationBatch() {
        AtomicInteger batches = new AtomicInteger();
        List<Article> received = new ArrayList<>();
        TitleTranslator translator = articles -> {
            batches.incrementAndGet();
            received.addAll(articles);
            return articles.stream().map(a -> a.withTranslatedTitle("KO:" + a.link())).toList();
        };
        // Simulates graceful degradation from a 429: the summarizer returns
        // the original article with summary == null.
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer(), translator);

        svc.ingest(feed("https://a", "https://b"), "F");

        assertEquals(1, batches.get());
        assertEquals(List.of("https://a", "https://b"),
                received.stream().map(Article::link).toList());
        assertEquals("KO:https://a", byLink(svc.latest(10), "https://a").translatedTitle());
        assertEquals("KO:https://b", byLink(svc.latest(10), "https://b").translatedTitle());
    }

    @Test
    void initialBacklogTranslationIsCappedToTheVisibleWindow() {
        AtomicInteger translatedCount = new AtomicInteger();
        TitleTranslator translator = articles -> {
            translatedCount.set(articles.size());
            return articles.stream().map(a -> a.withTranslatedTitle("KO:" + a.link())).toList();
        };
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer(), translator);
        String[] links = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "https://" + i)
                .toArray(String[]::new);

        svc.ingest(feed(links), "F");

        assertEquals(20, translatedCount.get());
        assertEquals("KO:https://20", byLink(svc.latest(30), "https://20").translatedTitle());
        assertNull(byLink(svc.latest(30), "https://21").translatedTitle());
    }

    @Test
    void successfulSummariesReduceTheTranslationBatchForTheVisibleWindow() {
        AtomicInteger translatedCount = new AtomicInteger();
        TitleTranslator translator = articles -> {
            translatedCount.set(articles.size());
            return articles.stream().map(a -> a.withTranslatedTitle("KO:" + a.link())).toList();
        };
        NewsService svc = new NewsService(new RssParser(), STAMPING, translator);
        String[] links = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "https://" + i)
                .toArray(String[]::new);

        svc.ingest(feed(links), "F");

        // The three successful summaries already enrich the first three
        // visible cards, leaving only 17 translation slots in the top 20.
        assertEquals(17, translatedCount.get());
        assertEquals("KO:https://20", byLink(svc.latest(30), "https://20").translatedTitle());
        assertNull(byLink(svc.latest(30), "https://21").translatedTitle());
    }

    @Test
    void koreanHeadlinesBypassTheTranslator() {
        AtomicInteger batches = new AtomicInteger();
        TitleTranslator translator = articles -> {
            batches.incrementAndGet();
            return articles;
        };
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer(), translator);
        String koreanFeed = """
                <rss version="2.0"><channel><title>F</title><item>
                  <title>AI 에이전트 장기 기억 전략</title>
                  <link>https://korean</link>
                  <description>이미 한국어로 작성된 기사 설명입니다.</description>
                </item></channel></rss>
                """;

        svc.ingest(koreanFeed, "Korean source");

        assertEquals(0, batches.get());
        assertNull(svc.latest(1).get(0).translatedTitle());
    }

    private static Article byLink(List<Article> articles, String link) {
        return articles.stream().filter(a -> a.link().equals(link)).findFirst().orElseThrow();
    }
}
