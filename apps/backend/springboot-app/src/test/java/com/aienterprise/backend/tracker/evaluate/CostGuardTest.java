package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class CostGuardTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void usageBeyondDailyCapBlocksFurtherCallsButResetsNextUtcDay() {
        Clock firstDay = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC);
        CostGuard guard = new CostGuard(jdbc, firstDay, new CostRates(10, 10, 1));

        assertTrue(guard.allow());
        guard.record("test-model", 1_500_000, 1_000_000, 0);
        assertFalse(guard.allow());

        Clock nextDay = Clock.fixed(Instant.parse("2026-07-13T00:00:01Z"), ZoneOffset.UTC);
        assertTrue(new CostGuard(jdbc, nextDay, new CostRates(10, 10, 1)).allow());
    }

    @Test
    void repeatedRecordsAggregateIntoOneDailyModelRow() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC);
        CostGuard guard = new CostGuard(jdbc, clock, new CostRates(1, 2, 0.5));

        guard.record("test-model", 100, 20, 10);
        guard.record("test-model", 50, 5, 0);

        Integer rows = jdbc.sql("SELECT COUNT(*) FROM llm_usage WHERE model = 'test-model'")
                .query(Integer.class).single();
        Integer calls = jdbc.sql("SELECT calls FROM llm_usage WHERE model = 'test-model'")
                .query(Integer.class).single();
        assertTrue(guard.allow());
        org.junit.jupiter.api.Assertions.assertEquals(1, rows);
        org.junit.jupiter.api.Assertions.assertEquals(2, calls);
    }
}
