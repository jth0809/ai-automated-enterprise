package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

import com.aienterprise.backend.tracker.transport.TransportProjectionService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
class TransportProjectionJobTest {

    private static final BigDecimal CENTRAL = new BigDecimal("200");
    private static final BigDecimal EASY = new BigDecimal("500");
    private static final BigDecimal HARD = new BigDecimal("100");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T01:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void runMonthlyUsesUtcDateAndDeclaredRuntimeTargets() {
        TransportProjectionService service = mock(TransportProjectionService.class);
        TransportProjectionJob job = new TransportProjectionJob(
                service, CLOCK, CENTRAL, EASY, HARD);

        job.runMonthly();

        verify(service).run(LocalDate.of(2026, 7, 15), CENTRAL, EASY, HARD);
    }

    @Test
    void scheduleIsMonthlyUtcAndProtectedByShedLock() throws Exception {
        Method method = TransportProjectionJob.class.getMethod("runMonthly");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertEquals("${tracker.transport-projection-cron:0 47 3 8 * *}",
                scheduled.cron());
        assertEquals("UTC", scheduled.zone());
        assertEquals("tracker-transport-projection", lock.name());
        assertEquals("PT10M", lock.lockAtMostFor());
    }

    @Test
    void beanRequiresBothTrackerAndTransportEconomicsFlags() {
        ConditionalOnProperty condition = TransportProjectionJob.class
                .getAnnotation(ConditionalOnProperty.class);

        assertArrayEquals(new String[] {"enabled", "transport-economics-enabled"},
                condition.name());
        assertEquals("true", condition.havingValue());
        assertFalse(context.containsBean("transportProjectionJob"));
    }

    @Test
    void applicationDefaultsRemainDarkAndUseApprovedAssumptions() {
        assertEquals("false", environment.getProperty(
                "tracker.transport-economics-enabled"));
        assertEquals("200", environment.getProperty(
                "tracker.transport-target-usd-per-kg"));
        assertEquals("500", environment.getProperty(
                "tracker.transport-target-easy-usd-per-kg"));
        assertEquals("100", environment.getProperty(
                "tracker.transport-target-hard-usd-per-kg"));
    }
}
