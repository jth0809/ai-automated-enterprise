package com.aienterprise.backend.tracker.ingest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.transport.TransportProjectionService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Low-frequency, fully gated refresh of the declared transport scenario. */
@Component
@ConditionalOnProperty(prefix = "tracker",
        name = {"enabled", "transport-economics-enabled"}, havingValue = "true")
public class TransportProjectionJob {

    private final TransportProjectionService service;
    private final Clock clock;
    private final BigDecimal central;
    private final BigDecimal easy;
    private final BigDecimal hard;

    @Autowired
    public TransportProjectionJob(
            TransportProjectionService service,
            @Value("${tracker.transport-target-usd-per-kg:200}") BigDecimal central,
            @Value("${tracker.transport-target-easy-usd-per-kg:500}") BigDecimal easy,
            @Value("${tracker.transport-target-hard-usd-per-kg:100}") BigDecimal hard) {
        this(service, Clock.systemUTC(), central, easy, hard);
    }

    TransportProjectionJob(
            TransportProjectionService service,
            Clock clock,
            BigDecimal central,
            BigDecimal easy,
            BigDecimal hard) {
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.central = Objects.requireNonNull(central, "central");
        this.easy = Objects.requireNonNull(easy, "easy");
        this.hard = Objects.requireNonNull(hard, "hard");
    }

    @Scheduled(cron = "${tracker.transport-projection-cron:0 47 3 8 * *}", zone = "UTC")
    @SchedulerLock(name = "tracker-transport-projection", lockAtMostFor = "PT10M")
    public void runMonthly() {
        service.run(LocalDate.now(clock), central, easy, hard);
    }
}
