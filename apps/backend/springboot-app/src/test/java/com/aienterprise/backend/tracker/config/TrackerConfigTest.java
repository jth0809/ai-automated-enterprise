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
import org.springframework.core.annotation.Order;

import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.core.LockProvider;

class TrackerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    JdbcClientAutoConfiguration.class,
                    FlywayAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:tracker-config;MODE=Oracle;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.flyway.enabled=true");

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
                        "tracker.feeds=NASA|https://www.nasa.gov/news-release/feed/,SPACENEWS|https://spacenews.com/feed/")
                .run(context -> {
                    assertThat(context).hasSingleBean(TrackerRepository.class);
                    assertThat(context).hasSingleBean(LockProvider.class);
                    @SuppressWarnings("unchecked")
                    List<FeedSpec> feeds = (List<FeedSpec>) context.getBean("trackerFeeds");
                    assertThat(feeds).containsExactly(
                            new FeedSpec("NASA", "https://www.nasa.gov/news-release/feed/"),
                            new FeedSpec("SPACENEWS", "https://spacenews.com/feed/"));
                });
    }

    @Test
    void backfillRunnerStaysOffInTestsButRemainsAvailableForTheDemoProfile() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.backfill-on-boot=true",
                        "spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("trackerBackfillRunner"));

        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.backfill-on-boot=true",
                        "spring.profiles.active=test,demo")
                .run(context -> assertThat(context)
                        .hasBean("trackerBackfillRunner"));
    }

    @Test
    void transportEconomicsRunnerStaysOffInTestsButIsAvailableAtRuntime() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.transport-economics-on-boot=true",
                        "spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("trackerTransportEconomicsRunner"));

        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.transport-economics-on-boot=true",
                        "spring.profiles.active=test,demo")
                .run(context -> assertThat(context)
                        .hasBean("trackerTransportEconomicsRunner"));
    }

    @Test
    void kIndexRunnerStaysOffInTestsButIsAvailableAtRuntime() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.k-index-on-boot=true",
                        "spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("trackerKIndexRunner"));

        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.k-index-on-boot=true",
                        "spring.profiles.active=test,demo")
                .run(context -> assertThat(context)
                        .hasBean("trackerKIndexRunner"));
    }

    @Test
    void humanPresenceRunnerStaysOffInTestsButIsAvailableAtRuntime() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.human-presence-on-boot=true",
                        "spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("trackerHumanPresenceRunner"));

        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.human-presence-on-boot=true",
                        "spring.profiles.active=test,demo")
                .run(context -> assertThat(context)
                        .hasBean("trackerHumanPresenceRunner"));
    }

    @Test
    void governanceRunnerStaysOffInTestsButIsAvailableAtRuntime() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.governance-on-boot=true",
                        "spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("trackerGovernanceRunner"));

        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.governance-on-boot=true",
                        "spring.profiles.active=test,demo")
                .run(context -> assertThat(context)
                        .hasBean("trackerGovernanceRunner"));
    }

    @Test
    void forecastReferenceRunnerStaysOffInTestsButIsAvailableAtRuntime() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.forecast-reference-on-boot=true",
                        "spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("trackerForecastReferenceRunner"));

        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.forecast-reference-on-boot=true",
                        "spring.profiles.active=test,demo")
                .run(context -> assertThat(context)
                        .hasBean("trackerForecastReferenceRunner"));
    }

    @Test
    void phase4BacktestRunnerIsDarkByDefaultAndOrderedAfterBackfill() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "spring.profiles.active=test,demo")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("trackerBacktestRunner"));

        runner.withUserConfiguration(TrackerConfig.class)
                .withPropertyValues(
                        "tracker.enabled=true",
                        "tracker.phase4-backtest-enabled=true",
                        "spring.profiles.active=test,demo")
                .run(context -> {
                    assertThat(context).hasBean("trackerBackfillRunner");
                    assertThat(context).hasBean("trackerBacktestRunner");
                    Order backfillOrder = context.getBeanFactory()
                            .findAnnotationOnBean("trackerBackfillRunner", Order.class);
                    Order backtestOrder = context.getBeanFactory()
                            .findAnnotationOnBean("trackerBacktestRunner", Order.class);
                    assertThat(backfillOrder).isNotNull();
                    assertThat(backtestOrder).isNotNull();
                    assertThat(backfillOrder.value())
                            .isLessThan(backtestOrder.value());
                });
    }
}
