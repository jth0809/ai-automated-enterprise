package com.aienterprise.backend.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;
import com.aienterprise.backend.tracker.domain.SourceDomainRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

class TrackerFeedPolicyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    JdbcClientAutoConfiguration.class,
                    FlywayAutoConfiguration.class))
            .withUserConfiguration(TrackerConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:tracker-policy;MODE=Oracle;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.flyway.enabled=true",
                    "tracker.enabled=true",
                    "tracker.backfill-on-boot=false");

    @Test
    void registeredHttpsFeedsAreAccepted() {
        runner.withPropertyValues("""
                tracker.feeds=NASA|https://www.nasa.gov/news-release/feed/,
                SPACENEWS|https://spacenews.com/feed/
                """.replace("\n", ""))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    @SuppressWarnings("unchecked")
                    List<FeedSpec> feeds = (List<FeedSpec>) context.getBean("trackerFeeds");
                    assertThat(feeds).containsExactly(
                            new FeedSpec("NASA", "https://www.nasa.gov/news-release/feed/"),
                            new FeedSpec("SPACENEWS", "https://spacenews.com/feed/"));
                });
    }

    @Test
    void configuredFeedMustMatchItsRegisteredSourceAndHost() {
        runner.withPropertyValues("tracker.feeds=NASA|https://evil.test/rss")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void configuredFeedMustUseHttps() {
        runner.withPropertyValues("tracker.feeds=NASA|http://www.nasa.gov/news-release/feed/")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void repositoryExposesExactPurposeAwareDomainPolicy() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            TrackerRepository repository = context.getBean(TrackerRepository.class);

            List<SourceDomainRow> policies = repository.findActiveSourceDomains();
            SourceDomainRow nasaFeed = policies.stream()
                    .filter(row -> row.sourceCode().equals("NASA") && row.domain().equals("www.nasa.gov"))
                    .findFirst()
                    .orElseThrow();

            assertThat(nasaFeed.purpose()).isEqualTo("BOTH");
            assertThat(repository.findActiveDomains(nasaFeed.sourceId(), "FEED"))
                    .containsExactly("www.nasa.gov");
            assertThat(repository.findActiveDomains(nasaFeed.sourceId(), "BODY"))
                    .containsExactlyInAnyOrder("www.nasa.gov", "science.nasa.gov");
            assertThat(repository.isRegisteredFeed("NASA", "www.nasa.gov")).isTrue();
            assertThat(repository.isRegisteredFeed("NASA", "science.nasa.gov")).isFalse();
            assertThat(repository.isRegisteredFeed("NASA", "evil.test")).isFalse();
        });
    }
}
