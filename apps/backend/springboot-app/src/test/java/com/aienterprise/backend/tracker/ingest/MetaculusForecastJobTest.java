package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.forecast.ForecastReference;
import com.aienterprise.backend.tracker.forecast.ForecastRepository;
import com.aienterprise.backend.tracker.forecast.MetaculusSnapshot;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class MetaculusForecastJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private ForecastRepository repository;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void addReferences() {
        repository.upsertReference(reference(
                "METACULUS-LANDING-METADATA", "LANDING", "post:3515"), "test-v1");
        repository.upsertReference(reference(
                "METACULUS-SETTLEMENT-PROXY-METADATA", "SETTLEMENT", "post:39073"),
                "test-v1");
    }

    @Test
    void importsTwoPostsAndCalculatesTheNinetyDayMean() {
        repository.saveCrowdObservation(
                "METACULUS-LANDING-METADATA", new BigDecimal("2040.0"),
                new BigDecimal("2040.0"), TODAY.minusDays(30), "a".repeat(64));
        MetaculusForecastJob job = job(postId -> Optional.of(new MetaculusSnapshot(
                postId, postId == 3515 ? new BigDecimal("2050.0")
                        : new BigDecimal("2090.0"), 0)));

        job.runOnce();

        assertEquals(new BigDecimal("2045.0"), repository.findLatestCrowdObservation(
                "METACULUS-LANDING-METADATA").orElseThrow().smoothedYear());
        assertEquals(new BigDecimal("2090.0"), repository.findLatestCrowdObservation(
                "METACULUS-SETTLEMENT-PROXY-METADATA").orElseThrow().smoothedYear());
    }

    @Test
    void oneFailureAndOneMissingAggregateDoNotMutateOtherHistory() {
        MetaculusForecastJob failedFirst = job(postId -> {
            if (postId == 3515) {
                throw new IllegalStateException("simulated failure");
            }
            return Optional.of(new MetaculusSnapshot(postId, new BigDecimal("2090.0"), 0));
        });
        failedFirst.runOnce();

        assertTrue(repository.findLatestCrowdObservation(
                "METACULUS-LANDING-METADATA").isEmpty());
        assertTrue(repository.findLatestCrowdObservation(
                "METACULUS-SETTLEMENT-PROXY-METADATA").isPresent());

        job(postId -> Optional.empty()).runOnce();
        assertEquals(1, repository.findCrowdWindow(
                "METACULUS-SETTLEMENT-PROXY-METADATA", TODAY, TODAY).size());
    }

    @Test
    void scheduleIsWeeklyUtcBoundedAndShedLocked() throws Exception {
        Method method = MetaculusForecastJob.class.getMethod("runOnce");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertEquals("${tracker.metaculus-cron:0 17 5 * * MON}", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
        assertEquals("tracker-metaculus-forecast", lock.name());
        assertEquals("PT15M", lock.lockAtMostFor());
        assertNull(method.getAnnotation(Transactional.class),
                "each post must commit independently so one DB failure cannot poison the next");
    }

    @Test
    void beanIsAbsentUntilBothAuthorizationFlagsAreEnabled() {
        assertFalse(context.containsBean("metaculusForecastJob"));
    }

    private MetaculusForecastJob job(MetaculusForecastJob.SnapshotFetcher fetcher) {
        return new MetaculusForecastJob(fetcher, repository, CLOCK, 99);
    }

    private static ForecastReference reference(String key, String track, String locator) {
        return new ForecastReference(
                key, "CROWD", "METACULUS", track, "Crowd question",
                "A bounded crowd comparison definition", "AWAITING_AUTHORIZATION",
                null, null, null, track.equals("SETTLEMENT") ? "PROXY" : "DIRECT",
                key.contains("LANDING")
                        ? "https://www.metaculus.com/questions/3515/"
                        : "https://www.metaculus.com/questions/39073/",
                locator, TODAY, "REVIEWED_REFERENCE", "1".repeat(64),
                "Reviewer-authored crowd metadata with enough context for job testing.");
    }
}
