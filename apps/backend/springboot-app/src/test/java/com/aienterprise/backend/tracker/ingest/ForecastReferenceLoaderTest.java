package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.forecast.ForecastReferenceValidatorTest;
import com.aienterprise.backend.tracker.forecast.ForecastRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class ForecastReferenceLoaderTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private ForecastRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void firstImportAndSameHashAreIdempotentWithoutTrackerMutation() {
        int nodes = count("capability_node");
        int events = count("event");
        int snapshots = count("pillar_snapshot");
        ForecastReferenceLoader loader = loader(validJson(), "forecast-test-v1");

        loader.loadIfNeeded();
        loader.loadIfNeeded();

        assertEquals(6, repository.findAllReferences().size());
        assertEquals(1, count("forecast_reference_import"));
        assertEquals(nodes, count("capability_node"));
        assertEquals(events, count("event"));
        assertEquals(snapshots, count("pillar_snapshot"));
    }

    @Test
    void changedHashForSameVersionFailsBeforeMutation() {
        loader(validJson(), "forecast-test-v1").loadIfNeeded();

        assertThrows(IllegalStateException.class,
                () -> loader(revisedJson(), "forecast-test-v1").loadIfNeeded());

        assertEquals("Reviewed question TEST-0",
                repository.findReference("TEST-0").orElseThrow().question());
        assertEquals(1, count("forecast_reference_import"));
    }

    @Test
    void aNewVersionUpsertsTheSameStableKeys() {
        loader(validJson(), "forecast-test-v1").loadIfNeeded();
        loader(revisedJson(), "forecast-test-v2").loadIfNeeded();

        assertEquals(6, repository.findAllReferences().size());
        assertEquals("Reviewed question TEST-0 revised",
                repository.findReference("TEST-0").orElseThrow().question());
        assertEquals(2, count("forecast_reference_import"));
    }

    @Test
    void invalidDatasetWritesNothing() {
        String invalid = validJson().replaceFirst(
                "https://www.nasa.gov", "http://attacker.example");

        assertThrows(IllegalStateException.class,
                () -> loader(invalid, "forecast-test-v1").loadIfNeeded());

        assertEquals(0, repository.findAllReferences().size());
        assertEquals(0, count("forecast_reference_import"));
    }

    private ForecastReferenceLoader loader(String json, String version) {
        return new ForecastReferenceLoader(
                repository,
                new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)),
                version,
                CLOCK);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Integer.class).single();
    }

    private static String validJson() {
        return ForecastReferenceValidatorTest.validDataset(6);
    }

    private static String revisedJson() {
        return ForecastReferenceValidatorTest.validDataset(6, " revised");
    }
}
