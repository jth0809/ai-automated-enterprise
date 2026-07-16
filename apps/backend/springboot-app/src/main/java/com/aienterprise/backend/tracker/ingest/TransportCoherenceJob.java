package com.aienterprise.backend.tracker.ingest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.transport.TransportCoherenceService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Runs the non-destructive matched-pair check for the previous UTC quarter. */
@Component
@ConditionalOnProperty(prefix = "tracker",
        name = {"enabled", "transport-economics-enabled"}, havingValue = "true")
public class TransportCoherenceJob {

    private final TransportCoherenceService service;
    private final Clock clock;

    @Autowired
    public TransportCoherenceJob(TransportCoherenceService service) {
        this(service, Clock.systemUTC());
    }

    TransportCoherenceJob(TransportCoherenceService service, Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(cron = "${tracker.coherence-cron:0 0 3 1 */3 *}", zone = "UTC")
    @SchedulerLock(name = "tracker-coherence", lockAtMostFor = "PT10M")
    public void runQuarterly() {
        LocalDate current = LocalDate.now(clock);
        service.runForQuarter(current.withDayOfMonth(1).minusDays(1));
    }
}
