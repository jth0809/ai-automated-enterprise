package com.aienterprise.backend.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;

class NewsIngestionSchedulerTest {

    private static final String FEED_XML = """
            <rss version="2.0"><channel><title>t</title>
            <item><title>Hello</title><link>https://news.example/a</link>\
            <description>d</description></item>
            </channel></rss>""";

    private final NewsService news = new NewsService(new RssParser(), new DisabledSummarizer());
    private final FeedFetcher fetcher = url -> FEED_XML;

    @Test
    void refreshIngestsConfiguredFeeds() {
        NewsIngestionScheduler scheduler = new NewsIngestionScheduler(
                news, fetcher, List.of(new FeedSpec("Example", "https://news.example/rss")));

        scheduler.refresh();

        assertThat(news.latest(10)).hasSize(1);
        assertThat(news.latest(10).get(0).title()).isEqualTo("Hello");
    }

    @Test
    void runsAnInitialIngestOnStartup() {
        NewsIngestionScheduler scheduler = new NewsIngestionScheduler(
                news, fetcher, List.of(new FeedSpec("Example", "https://news.example/rss")));

        scheduler.onStartup();

        assertThat(news.latest(10)).hasSize(1);
    }

    @Test
    void startupIngestIsWiredToApplicationReady() throws Exception {
        Method onStartup = NewsIngestionScheduler.class.getMethod("onStartup");
        EventListener listener = onStartup.getAnnotation(EventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }
}
