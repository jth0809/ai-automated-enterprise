package com.aienterprise.backend.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;

class NewsConfigTest {

    @Test
    void parsesSourceUrlPairs() {
        List<FeedSpec> specs = NewsConfig.parseFeeds(
                "Google News|https://news.google.com/rss , Other|https://feeds.example/rss");
        assertThat(specs).containsExactly(
                new FeedSpec("Google News", "https://news.google.com/rss"),
                new FeedSpec("Other", "https://feeds.example/rss"));
    }

    @Test
    void acceptsBareUrlUsingItsHostAsSource() {
        List<FeedSpec> specs = NewsConfig.parseFeeds(
                "https://news.google.com/rss/search?q=kubernetes");
        assertThat(specs).containsExactly(
                new FeedSpec("news.google.com", "https://news.google.com/rss/search?q=kubernetes"));
    }

    @Test
    void skipsEntriesThatAreNeitherPairsNorUrls() {
        assertThat(NewsConfig.parseFeeds("garbage")).isEmpty();
        assertThat(NewsConfig.parseFeeds("|https://no-source.example")).isEmpty();
        assertThat(NewsConfig.parseFeeds("no-url|")).isEmpty();
    }

    @Test
    void returnsNoFeedsForBlankConfig() {
        assertThat(NewsConfig.parseFeeds("")).isEmpty();
        assertThat(NewsConfig.parseFeeds(null)).isEmpty();
    }
}
