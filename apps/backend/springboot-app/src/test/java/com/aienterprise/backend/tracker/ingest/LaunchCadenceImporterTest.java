package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.layerb.LaunchCadenceAggregator;
import com.aienterprise.backend.tracker.layerb.LaunchRecord;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LaunchCadenceImporterTest {

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void importingTheSameCompletedYearTwiceUpsertsTwoMeasuredMetrics() {
        LaunchCadenceImporter importer = new LaunchCadenceImporter(
                repository, new LaunchCadenceAggregator());
        List<LaunchRecord> launches = List.of(
                launch("a", "2024-01-01T00:00:00Z", "Success", true),
                launch("b", "2024-06-01T00:00:00Z", "Failure", false),
                launch("future", "2025-01-01T00:00:00Z", "Success", true));
        LocalDate accessedOn = LocalDate.of(2026, 7, 15);

        assertEquals(2, importer.importYear(2024, launches, accessedOn));
        assertEquals(2, importer.importYear(2024, launches, accessedOn));

        assertEquals(2, repository.countLayerBMetrics());
        assertEquals(2, jdbc.sql("""
                SELECT COUNT(*) FROM layer_b_metric
                 WHERE observed_on = DATE '2024-12-31'
                   AND basis = 'MEASURED'
                   AND source_url = :url
                """).param("url", "https://ll.thespacedevs.com/2.3.0/launches/")
                .query(Integer.class).single());
    }

    private static LaunchRecord launch(
            String id, String net, String status, boolean successful) {
        return new LaunchRecord(
                id, id, Instant.parse(net), "Provider", status, successful, "");
    }
}
