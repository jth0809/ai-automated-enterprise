package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

import com.aienterprise.backend.tracker.transport.TransportCoherenceService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
class TransportCoherenceJobTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-01T03:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private ApplicationContext context;

    @Test
    void runQuarterlyUsesThePreviousCompletedUtcQuarter() {
        TransportCoherenceService service = mock(TransportCoherenceService.class);
        TransportCoherenceJob job = new TransportCoherenceJob(service, CLOCK);

        job.runQuarterly();

        verify(service).runForQuarter(LocalDate.of(2026, 6, 30));
    }

    @Test
    void scheduleIsQuarterlyUtcAndProtectedByShedLock() throws Exception {
        Method method = TransportCoherenceJob.class.getMethod("runQuarterly");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertEquals("${tracker.coherence-cron:0 0 3 1 */3 *}", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
        assertEquals("tracker-coherence", lock.name());
        assertEquals("PT10M", lock.lockAtMostFor());
    }

    @Test
    void beanRequiresBothTrackerAndTransportEconomicsFlags() {
        ConditionalOnProperty condition = TransportCoherenceJob.class
                .getAnnotation(ConditionalOnProperty.class);

        assertArrayEquals(new String[] {"enabled", "transport-economics-enabled"},
                condition.name());
        assertEquals("true", condition.havingValue());
        assertFalse(context.containsBean("transportCoherenceJob"));
    }
}
