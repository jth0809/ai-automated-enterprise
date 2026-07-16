package com.aienterprise.backend.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class ForecastRepositoryTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 15);

    @Autowired
    private ForecastRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void addCrowdReference() {
        repository.upsertReference(crowdReference(), "forecast-test-v1");
    }

    @Test
    void savesAndReadsIdentifiedCrowdHistory() {
        long id = repository.saveCrowdObservation(
                "METACULUS-LANDING-METADATA", new BigDecimal("2045.2"),
                new BigDecimal("2044.8"), AS_OF, "a".repeat(64));

        var latest = repository.findLatestCrowdObservation(
                "METACULUS-LANDING-METADATA").orElseThrow();
        assertEquals(id, latest.id());
        assertEquals(new BigDecimal("2045.2"), latest.forecastYear());
        assertEquals(new BigDecimal("2044.8"), latest.smoothedYear());
        assertEquals(90, latest.smoothingWindowDays());
    }

    @Test
    void sameHashAndValueAreIdempotentButSameDayConflictFails() {
        long first = repository.saveCrowdObservation(
                "METACULUS-LANDING-METADATA", new BigDecimal("2045.2"),
                new BigDecimal("2044.8"), AS_OF, "a".repeat(64));
        long repeated = repository.saveCrowdObservation(
                "METACULUS-LANDING-METADATA", new BigDecimal("2045.2"),
                new BigDecimal("2044.8"), AS_OF, "a".repeat(64));

        assertEquals(first, repeated);
        assertThrows(IllegalStateException.class, () -> repository.saveCrowdObservation(
                "METACULUS-LANDING-METADATA", new BigDecimal("2046.0"),
                new BigDecimal("2045.0"), AS_OF, "b".repeat(64)));
    }

    @Test
    void rangeQueryIncludesOnlyIdentifiedRowsAndRequestedDates() {
        repository.saveCrowdObservation(
                "METACULUS-LANDING-METADATA", new BigDecimal("2045.0"),
                new BigDecimal("2045.0"), AS_OF.minusDays(89), "a".repeat(64));
        repository.saveCrowdObservation(
                "METACULUS-LANDING-METADATA", new BigDecimal("2046.0"),
                new BigDecimal("2045.5"), AS_OF, "b".repeat(64));
        jdbc.sql("""
                INSERT INTO external_forecast
                  (source_type, source_name, question, forecast_year, retrieved_on)
                VALUES ('CROWD', 'legacy', 'legacy', 2200.0, DATE '2026-07-15')
                """).update();
        jdbc.sql("""
                INSERT INTO external_forecast
                  (source_type, source_name, question, forecast_year,
                   smoothed_year, retrieved_on, forecast_key,
                   observation_sha256, observation_status, smoothing_window_days)
                VALUES
                  ('INSTITUTIONAL', 'invalid-for-crowd-window', 'invalid', 2040.0,
                   2040.0, DATE '2026-07-14', 'METACULUS-LANDING-METADATA',
                   :sha, 'CURRENT', 90)
                """).param("sha", "c".repeat(64)).update();

        var values = repository.findCrowdWindow(
                "METACULUS-LANDING-METADATA", AS_OF.minusDays(89), AS_OF);

        assertEquals(2, values.size());
        assertTrue(values.stream().allMatch(value -> value.forecastKey() != null));
    }

    @Test
    void rejectsUnknownOrInstitutionalReferenceKeys() {
        repository.upsertReference(institutionalReference(), "forecast-test-v1");

        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCrowdObservation(
                        "UNKNOWN", new BigDecimal("2040"), new BigDecimal("2040"),
                        AS_OF, "c".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCrowdObservation(
                        "NASA-LANDING", new BigDecimal("2040"), new BigDecimal("2040"),
                        AS_OF, "d".repeat(64)));
    }

    @Test
    void rejectsObservationPrecisionTheLedgerWouldOtherwiseRound() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCrowdObservation(
                        "METACULUS-LANDING-METADATA", new BigDecimal("2045.25"),
                        new BigDecimal("2045.3"), AS_OF, "e".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveCrowdObservation(
                        "METACULUS-LANDING-METADATA", new BigDecimal("2045.2"),
                        new BigDecimal("2045.25"), AS_OF, "f".repeat(64)));
    }

    private static ForecastReference crowdReference() {
        return new ForecastReference(
                "METACULUS-LANDING-METADATA", "CROWD", "METACULUS", "LANDING",
                "Landing", "First successful human landing", "AWAITING_AUTHORIZATION",
                null, null, null, "DIRECT",
                "https://www.metaculus.com/questions/3515/", "post:3515", AS_OF,
                "REVIEWED_REFERENCE", "1".repeat(64),
                "Reviewer-authored crowd metadata summary with sufficient context.");
    }

    private static ForecastReference institutionalReference() {
        return new ForecastReference(
                "NASA-LANDING", "INSTITUTIONAL", "NASA", "LANDING",
                "NASA landing", "First successful human landing", "UNDATED",
                null, null, null, "DIRECT",
                "https://www.nasa.gov/moontomarsarchitecture-strategyandobjectives/",
                "current page", AS_OF, "REVIEWED_REFERENCE", "2".repeat(64),
                "Reviewer-authored institutional summary with sufficient context.");
    }
}
