package com.aienterprise.backend.tracker.ingest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.layerb.LaunchCadenceAggregator;
import com.aienterprise.backend.tracker.layerb.LaunchLibraryClient;
import com.aienterprise.backend.tracker.layerb.LaunchRecord;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Low-frequency LL2 collector for finalized annual Layer B measurements.
 * It never creates capability events; Layer C promotion remains deferred until
 * the separately gated live classifier is activated.
 */
@Component
@ConditionalOnProperty(
        prefix = "tracker", name = { "enabled", "ll2-enabled" }, havingValue = "true")
public class LaunchLibraryJob {

    private static final Logger log = LoggerFactory.getLogger(LaunchLibraryJob.class);

    private final LaunchLibraryClient client;
    private final LaunchCadenceImporter importer;
    private final Clock clock;
    private final int maxPages;

    @Autowired
    public LaunchLibraryJob(
            TrackerRepository repository,
            @Value("${tracker.ll2-max-pages:10}") int maxPages) {
        this(LaunchLibraryClient.overHttp(),
                new LaunchCadenceImporter(repository, new LaunchCadenceAggregator()),
                Clock.systemUTC(), maxPages);
    }

    LaunchLibraryJob(
            LaunchLibraryClient client,
            LaunchCadenceImporter importer,
            Clock clock,
            int maxPages) {
        this.client = client;
        this.importer = importer;
        this.clock = clock;
        this.maxPages = maxPages;
    }

    @Scheduled(cron = "${tracker.ll2-cron:0 17 3 8 * *}", zone = "UTC")
    @SchedulerLock(name = "tracker-ll2-layer-b", lockAtMostFor = "PT20M")
    @Transactional
    public void runMonthly() {
        LocalDate accessedOn = LocalDate.now(clock);
        int completedYear = accessedOn.getYear() - 1;
        Optional<List<LaunchRecord>> fetched = client.fetchAll(
                LaunchLibraryClient.yearUri(completedYear), maxPages);
        if (fetched.isEmpty()) {
            log.warn("LL2 annual launch collection incomplete for {}; Layer B unchanged",
                    completedYear);
            return;
        }
        int imported = importer.importYear(completedYear, fetched.get(), accessedOn);
        log.info("LL2 annual launch collection imported {} Layer B metrics for {}",
                imported, completedYear);
    }
}
