package com.aienterprise.backend.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.core.LockProvider;

class TrackerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    JdbcClientAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:tracker-config;MODE=Oracle;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=");

    @Test
    void trackerBeansAreAbsentWhenFeatureFlagIsDisabledByDefault() {
        runner.withUserConfiguration(TrackerConfig.class).run(context -> {
            assertThat(context).doesNotHaveBean(TrackerRepository.class);
            assertThat(context).doesNotHaveBean(LockProvider.class);
            assertThat(context).doesNotHaveBean("trackerFeeds");
        });
    }

    @Test
    void trackerBeansAndParsedFeedsExistWhenEnabled() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.feeds=NASA|https://www.nasa.gov/rss.xml,https://spacenews.com/feed/")
                .run(context -> {
                    assertThat(context).hasSingleBean(TrackerRepository.class);
                    assertThat(context).hasSingleBean(LockProvider.class);
                    @SuppressWarnings("unchecked")
                    List<FeedSpec> feeds = (List<FeedSpec>) context.getBean("trackerFeeds");
                    assertThat(feeds).containsExactly(
                            new FeedSpec("NASA", "https://www.nasa.gov/rss.xml"),
                            new FeedSpec("spacenews.com", "https://spacenews.com/feed/"));
                });
    }
}
