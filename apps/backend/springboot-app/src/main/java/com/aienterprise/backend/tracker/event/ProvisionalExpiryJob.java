package com.aienterprise.backend.tracker.event;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class ProvisionalExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ProvisionalExpiryJob.class);

    private final TrackerRepository repository;

    public ProvisionalExpiryJob(TrackerRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${tracker.expiry-cron:0 10 1 * * *}")
    @SchedulerLock(name = "tracker-expiry", lockAtLeastFor = "PT1M")
    public void runOnce() {
        int expired = repository.expireProvisionalEvents(LocalDate.now(ZoneOffset.UTC));
        if (expired > 0) {
            log.info("tracker expired {} provisional events past their verification window", expired);
        }
    }
}
