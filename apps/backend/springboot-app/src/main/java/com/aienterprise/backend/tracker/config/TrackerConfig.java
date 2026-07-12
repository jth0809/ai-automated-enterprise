package com.aienterprise.backend.tracker.config;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.aienterprise.backend.news.NewsConfig;
import com.aienterprise.backend.news.NewsIngestionScheduler.FeedSpec;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class TrackerConfig {

    @Bean
    TrackerRepository trackerRepository(JdbcClient jdbcClient) {
        return new TrackerRepository(jdbcClient);
    }

    @Bean(name = "trackerFeeds")
    List<FeedSpec> trackerFeeds(@Value("${tracker.feeds:}") String feeds) {
        return NewsConfig.parseFeeds(feeds);
    }

    @Bean
    LockProvider trackerLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(new JdbcTemplate(dataSource));
    }
}
