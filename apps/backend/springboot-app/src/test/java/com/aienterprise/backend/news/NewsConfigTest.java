package com.aienterprise.backend.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;

class NewsConfigTest {

    private static final String MODEL = "claude-opus-4-8";

    @Test
    void summarizerFallsBackToDisabledWithoutApiKey() {
        assertThat(NewsConfig.summarizer(RestClient.builder(), null, MODEL))
                .isInstanceOf(DisabledSummarizer.class);
        assertThat(NewsConfig.summarizer(RestClient.builder(), "", MODEL))
                .isInstanceOf(DisabledSummarizer.class);
        assertThat(NewsConfig.summarizer(RestClient.builder(), "   ", MODEL))
                .isInstanceOf(DisabledSummarizer.class);
    }

    @Test
    void summarizerUsesAnthropicWhenApiKeyIsPresent() {
        assertThat(NewsConfig.summarizer(RestClient.builder(), "sk-ant-test", MODEL))
                .isInstanceOf(AnthropicSummarizer.class);
    }

    @Test
    void titleTranslatorFallsBackToDisabledWithoutApiKey() {
        assertThat(NewsConfig.titleTranslator(RestClient.builder(), null, MODEL))
                .isInstanceOf(DisabledTitleTranslator.class);
        assertThat(NewsConfig.titleTranslator(RestClient.builder(), " ", MODEL))
                .isInstanceOf(DisabledTitleTranslator.class);
    }

    @Test
    void titleTranslatorUsesAnthropicWhenApiKeyIsPresent() {
        assertThat(NewsConfig.titleTranslator(RestClient.builder(), "sk-ant-test", MODEL))
                .isInstanceOf(AnthropicTitleTranslator.class);
    }

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
