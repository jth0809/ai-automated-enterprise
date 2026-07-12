package com.aienterprise.backend.tracker.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class ProvisionalExpiryJobTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private ProvisionalExpiryJob job;

    @Test
    void overdueProvisionalEventsExpireWhileFreshOnesRemain() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long overdue = event("P1-ORBIT-REFUEL|FLIGHT_TEST|overdue|2900", today.minusDays(1));
        long fresh = event("P1-ORBIT-REFUEL|FLIGHT_TEST|fresh|2901", today.plusDays(10));

        job.runOnce();

        assertEquals("EXPIRED", status(overdue));
        assertEquals("PROVISIONAL", status(fresh));
    }

    private long event(String naturalKey, LocalDate expiresOn) {
        long nodeId = jdbc.sql("SELECT id FROM capability_node WHERE code = 'P1-ORBIT-REFUEL'")
                .query(Long.class).single();
        return repository.upsertEventByNaturalKey(naturalKey, EventRow.draft(
                nodeId, "FLIGHT_TEST", 6, "SpaceX", LocalDate.of(2026, 1, 30),
                "CLAIMED", expiresOn, repository.activeRubricVersionId()));
    }

    private String status(long eventId) {
        return jdbc.sql("SELECT event_status FROM event WHERE id = :id")
                .param("id", eventId).query(String.class).single();
    }
}
