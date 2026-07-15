package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.layerb.LaunchCadenceAggregator;
import com.aienterprise.backend.tracker.layerb.LaunchLibraryClient;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LaunchLibraryJobTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T01:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private ApplicationContext context;

    @Test
    void importsOnlyThePreviousCompletedUtcYearAndRemainsIdempotent() {
        List<String> requested = new ArrayList<>();
        LaunchLibraryClient client = new LaunchLibraryClient(uri -> {
            requested.add(uri.toString());
            return """
                    {"next":null,"results":[
                      {"id":"a","name":"Success","net":"2025-01-02T00:00:00Z",
                       "status":{"id":3,"abbrev":"Success"},
                       "launch_service_provider":{"name":"Provider"}},
                      {"id":"b","name":"Failure","net":"2025-06-02T00:00:00Z",
                       "status":{"id":4,"abbrev":"Failure"},
                       "launch_service_provider":{"name":"Provider"}},
                      {"id":"future","name":"Future","net":"2026-01-02T00:00:00Z",
                       "status":{"id":3,"abbrev":"Success"},
                       "launch_service_provider":{"name":"Provider"}}
                    ]}
                    """;
        });
        LaunchLibraryJob job = job(client);

        job.runMonthly();
        job.runMonthly();

        assertEquals(List.of(
                LaunchLibraryClient.yearUri(2025).toString(),
                LaunchLibraryClient.yearUri(2025).toString()), requested);
        assertEquals(2, repository.countLayerBMetrics());
        assertEquals(2, repository.findLatestLayerBByPillar().stream()
                .filter(metric -> metric.observedOn().toString().equals("2025-12-31"))
                .count());
    }

    @Test
    void incompleteOrUnsafePaginationNeverPersistsAPartialMeasurement() {
        LaunchLibraryClient client = new LaunchLibraryClient(uri -> """
                {"next":"https://attacker.example/launches/?offset=100","results":[
                  {"id":"a","name":"Success","net":"2025-01-02T00:00:00Z",
                   "status":{"id":3,"abbrev":"Success"},
                   "launch_service_provider":{"name":"Provider"}}]}
                """);

        job(client).runMonthly();

        assertEquals(0, repository.countLayerBMetrics());
    }

    @Test
    void scheduleIsLowFrequencyUtcAndProtectedByShedLock() throws Exception {
        Method method = LaunchLibraryJob.class.getMethod("runMonthly");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertEquals("${tracker.ll2-cron:0 17 3 8 * *}", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
        assertEquals("tracker-ll2-layer-b", lock.name());
        assertEquals("PT20M", lock.lockAtMostFor());
    }

    @Test
    void liveLl2PollingBeanIsAbsentUnlessItsSeparateFlagIsEnabled() {
        assertFalse(context.containsBean("launchLibraryJob"));
    }

    private LaunchLibraryJob job(LaunchLibraryClient client) {
        return new LaunchLibraryJob(
                client,
                new LaunchCadenceImporter(repository, new LaunchCadenceAggregator()),
                CLOCK,
                10);
    }
}
