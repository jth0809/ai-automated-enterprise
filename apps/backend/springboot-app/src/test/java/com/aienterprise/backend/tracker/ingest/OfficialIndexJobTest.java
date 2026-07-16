package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.collection.OfficialIndexChannel;
import com.aienterprise.backend.tracker.collection.OfficialIndexParser;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class OfficialIndexJobTest {

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationContext context;

    @Test
    void importsMetadataCandidatesIdempotentlyAndKeepsThemQuarantined() {
        OfficialIndexJob job = job((uri, hosts) -> page(uri,
                "<a href=\"/Successful_Test.html\">Successful test</a> July 11, 2025"),
                List.of(OfficialIndexChannel.ISRO_PRESS));

        job.runOnce();
        job.runOnce();

        assertEquals(1, candidateCount("ISRO"));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM article
                 WHERE evaluation_allowed = 'N'
                   AND body_extraction_status = 'SKIPPED'
                   AND body IS NULL
                """).query(Integer.class).single());
        assertEquals(0, repository.findByStatus("INGESTED", 10).size());
    }

    @Test
    void oneBrokenChannelDoesNotAbortTheRemainingChannels() {
        OfficialIndexJob job = job((uri, hosts) -> {
            if (uri.equals(OfficialIndexChannel.ISRO_PRESS.indexUri())) {
                throw new IllegalStateException("simulated ISRO outage");
            }
            return page(uri, """
                    <a href="/english/n6465645/n6465648/c10652198/content.html">
                      Mars cooperation notice</a> 03/11/2025
                    """);
        }, List.of(OfficialIndexChannel.ISRO_PRESS, OfficialIndexChannel.CNSA_POLICY));

        job.runOnce();

        assertEquals(0, candidateCount("ISRO"));
        assertEquals(1, candidateCount("CNSA"));
    }

    @Test
    void scheduleIsWeeklyUtcBoundedAndShedLocked() throws Exception {
        Method method = OfficialIndexJob.class.getMethod("runOnce");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertEquals("${tracker.official-index-cron:0 23 4 * * WED}", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
        assertEquals("tracker-official-index", lock.name());
        assertEquals("PT20M", lock.lockAtMostFor());
        assertNull(method.getAnnotation(Transactional.class),
                "each channel must commit independently so one DB failure cannot poison the next");
    }

    @Test
    void liveCollectorBeanIsAbsentUnlessItsSeparateFlagIsEnabled() {
        assertFalse(context.containsBean("officialIndexJob"));
    }

    private OfficialIndexJob job(
            OfficialIndexJob.IndexPageFetcher fetcher,
            List<OfficialIndexChannel> channels) {
        return new OfficialIndexJob(fetcher, new OfficialIndexParser(), repository,
                channels, 40);
    }

    private FetchedPage page(java.net.URI uri, String html) {
        return new FetchedPage(uri, "text/html", "utf-8",
                html.getBytes(StandardCharsets.UTF_8));
    }

    private int candidateCount(String sourceCode) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM article a
                  JOIN source_registry s ON s.id = a.source_id
                 WHERE s.code = :sourceCode
                """).param("sourceCode", sourceCode).query(Integer.class).single();
    }
}
