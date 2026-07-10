package com.aienterprise.backend.news;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory news store for P1: ingest parses a feed, summarizes the newest
 * few articles (budgeted), batch-translates the headlines of the rest, and
 * stores everything deduplicated by link. Persistence to ATP (and
 * Redis-backed dedup) is a later step — the store is intentionally kept
 * behind this class so that swap is local. {@code latest} sorts by publish
 * date so refreshed articles surface regardless of insertion order.
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

    /**
     * Maximum newly stored articles considered for AI enrichment per ingest.
     * The public feed shows 20 items by default, so enriching a larger initial
     * RSS backlog would spend tokens on articles that are not visible.
     */
    static final int MAX_ENRICHED_ARTICLES_PER_INGEST = 20;

    private final RssParser parser;
    private final ArticleSummarizer summarizer;
    private final TitleTranslator translator;
    private final Map<String, Article> byLink = new LinkedHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public NewsService(RssParser parser, ArticleSummarizer summarizer) {
        this(parser, summarizer, new DisabledTitleTranslator());
    }

    public NewsService(RssParser parser, ArticleSummarizer summarizer, TitleTranslator translator) {
        this.parser = parser;
        this.summarizer = summarizer;
        this.translator = translator;
    }

    public void ingest(String feedXml, String source) {
        List<Article> parsed = parser.parse(feedXml, source);

        // Snapshot which links are new under the read lock only; summarize()
        // is a slow outbound Claude call and must never run while any lock is
        // held, or /api/news readers queue behind it for seconds.
        List<Article> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        lock.readLock().lock();
        try {
            for (Article article : parsed) {
                if (article.link() != null && !byLink.containsKey(article.link())
                        && seen.add(article.link())) {
                    fresh.add(article);
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        List<Article> prepared = new ArrayList<>(fresh.size());
        List<Article> needsTranslation = new ArrayList<>();
        List<Integer> translationIndexes = new ArrayList<>();
        int summarized = 0;
        for (int i = 0; i < fresh.size(); i++) {
            Article article = fresh.get(i);
            Article enriched;
            if (summarized < MAX_SUMMARIES_PER_INGEST) {
                enriched = summarizer.summarize(article);
                summarized++;
            } else {
                enriched = article;
            }
            prepared.add(enriched);
            if (enriched.summary() == null
                    && i < MAX_ENRICHED_ARTICLES_PER_INGEST
                    && needsKoreanTranslation(enriched.title())) {
                needsTranslation.add(enriched);
                translationIndexes.add(i);
            }
        }

        // Translation is another outbound Claude call, so it also runs with
        // no store lock held. The translator contract preserves order.
        if (!needsTranslation.isEmpty()) {
            List<Article> translated = translator.translateTitles(needsTranslation);
            int replacements = Math.min(translated.size(), translationIndexes.size());
            for (int i = 0; i < replacements; i++) {
                prepared.set(translationIndexes.get(i), translated.get(i));
            }
        }

        lock.writeLock().lock();
        try {
            // Re-check dedup: a concurrent ingest may have stored a link
            // while AI enrichment ran; the first stored article wins.
            for (Article article : prepared) {
                byLink.putIfAbsent(article.link(), article);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Article> latest(int limit) {
        lock.readLock().lock();
        try {
            // Sort by publish date, newest first. Insertion order won't do:
            // hourly refreshes append newer articles at the tail, which froze
            // the visible window on the first post-restart batch. Undated or
            // unparseable articles sort last (stable, so they keep feed order).
            List<Article> all = new ArrayList<>(byLink.values());
            all.sort(Comparator.comparing(
                    NewsService::publishedInstant,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return all.subList(0, Math.min(limit, all.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** RFC-1123 pubDate as an Instant, or null when absent/unparseable. */
    private static Instant publishedInstant(Article article) {
        if (article.publishedAt() == null) {
            return null;
        }
        try {
            return ZonedDateTime
                    .parse(article.publishedAt(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Only non-blank headlines without any Hangul need Korean translation. */
    private static boolean needsKoreanTranslation(String title) {
        return title != null
                && !title.isBlank()
                && title.codePoints().noneMatch(codePoint ->
                        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL);
    }
}
