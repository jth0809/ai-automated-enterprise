package com.aienterprise.backend.tracker.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

import com.aienterprise.backend.news.NewsConfig;
import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ingest.ArticleBodyExtractor;
import com.aienterprise.backend.tracker.ingest.ArticlePageFetcher;
import com.aienterprise.backend.tracker.ingest.BackfillLoader;
import com.aienterprise.backend.tracker.ingest.LayerBLoader;
import com.aienterprise.backend.tracker.ingest.JdkPageTransport;
import com.aienterprise.backend.tracker.ingest.JsoupReadabilityExtractor;
import com.aienterprise.backend.tracker.ingest.TransportEconomicsLoader;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class TrackerConfig {

    @Bean
    TrackerRepository trackerRepository(
            JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        return new TrackerRepository(jdbcClient, jdbcTemplate);
    }

    @Bean(name = "trackerFeeds")
    @DependsOnDatabaseInitialization
    List<FeedSpec> trackerFeeds(
            @Value("${tracker.feeds:}") String configuredFeeds,
            TrackerRepository repository) {
        if (configuredFeeds == null || configuredFeeds.isBlank()) {
            return List.of();
        }

        List<FeedSpec> feeds = NewsConfig.parseFeeds(configuredFeeds);
        int configuredCount = configuredFeeds.split(",", -1).length;
        if (feeds.size() != configuredCount) {
            throw new IllegalArgumentException(
                    "tracker.feeds must contain nonblank source|https-url entries");
        }

        for (FeedSpec feed : feeds) {
            validateFeed(feed, repository);
        }
        return List.copyOf(feeds);
    }

    private static void validateFeed(FeedSpec feed, TrackerRepository repository) {
        URI uri;
        try {
            uri = new URI(feed.url());
        } catch (URISyntaxException invalidUri) {
            throw new IllegalArgumentException("Invalid tracker feed URI for " + feed.source(), invalidUri);
        }

        boolean invalidEndpoint = !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443);
        if (feed.source() == null || feed.source().isBlank() || invalidEndpoint) {
            throw new IllegalArgumentException("Tracker feed must use a named HTTPS endpoint: " + feed.url());
        }
        if (!repository.isRegisteredFeed(feed.source(), uri.getHost())) {
            throw new IllegalArgumentException(
                    "Tracker feed is not registered for source/host: " + feed.source() + "/" + uri.getHost());
        }
    }

    @Bean
    LockProvider trackerLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(new JdbcTemplate(dataSource));
    }

    @Bean
    ArticlePageFetcher articlePageFetcher() {
        return new ArticlePageFetcher(new JdkPageTransport());
    }

    @Bean
    ArticleBodyExtractor articleBodyExtractor() {
        return new JsoupReadabilityExtractor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "tracker", name = "backfill-on-boot", havingValue = "true", matchIfMissing = true)
    @Profile("!test | demo")
    ApplicationRunner trackerBackfillRunner(ObjectProvider<BackfillLoader> backfillLoader) {
        return args -> backfillLoader.ifAvailable(BackfillLoader::loadDatasetIfNeeded);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tracker", name = "layer-b-on-boot", havingValue = "true", matchIfMissing = true)
    @Profile("!test | demo")
    ApplicationRunner trackerLayerBRunner(ObjectProvider<LayerBLoader> layerBLoader) {
        return args -> layerBLoader.ifAvailable(LayerBLoader::loadIfNeeded);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tracker", name = "transport-economics-on-boot",
            havingValue = "true", matchIfMissing = true)
    @Profile("!test | demo")
    ApplicationRunner trackerTransportEconomicsRunner(
            ObjectProvider<TransportEconomicsLoader> transportEconomicsLoader) {
        return args -> transportEconomicsLoader.ifAvailable(
                TransportEconomicsLoader::loadIfNeeded);
    }
}
