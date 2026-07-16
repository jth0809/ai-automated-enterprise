package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
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

import com.aienterprise.backend.tracker.kindex.KIndexRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class KIndexLoaderTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private KIndexRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void firstImportCalculatesObservationsAndSameHashIsANoOp() {
        KIndexLoader loader = loader(validCsv(), "k-index-test-v1");

        loader.loadIfNeeded();
        loader.loadIfNeeded();

        assertEquals(10, repository.findAll().size());
        assertEquals(new BigDecimal("0.7058"),
                repository.findAll().getLast().kValue());
        assertEquals(1, importCount());
    }

    @Test
    void changedHashForTheSameVersionFailsWithoutChangingObservations() {
        loader(validCsv(), "k-index-test-v1").loadIfNeeded();

        assertThrows(IllegalStateException.class, () -> loader(
                validCsv().replace("2024,100009.0", "2024,110009.0"),
                "k-index-test-v1").loadIfNeeded());

        assertEquals(new BigDecimal("100009.000"),
                repository.findAll().getLast().primaryEnergyTwh());
        assertEquals(1, importCount());
    }

    @Test
    void newVersionCanCorrectHistoricalRowsByUpsert() {
        loader(validCsv(), "k-index-test-v1").loadIfNeeded();
        loader(validCsv().replace("2024,100009.0", "2024,110009.0"),
                "k-index-test-v2").loadIfNeeded();

        assertEquals(10, repository.findAll().size());
        assertEquals(new BigDecimal("110009.000"),
                repository.findAll().getLast().primaryEnergyTwh());
        assertEquals("k-index-test-v2",
                repository.findAll().getLast().datasetVersion());
        assertEquals(2, importCount());
    }

    @Test
    void invalidCsvRollsBackAllWrites() {
        String invalid = validCsv().replace("2020,100005.0", "2020,0");

        assertThrows(IllegalStateException.class,
                () -> loader(invalid, "k-index-test-v1").loadIfNeeded());

        assertEquals(0, repository.findAll().size());
        assertEquals(0, importCount());
    }

    private KIndexLoader loader(String csv, String version) {
        return new KIndexLoader(
                repository,
                new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)),
                version,
                CLOCK);
    }

    private int importCount() {
        return jdbc.sql("SELECT COUNT(*) FROM k_index_import")
                .query(Integer.class).single();
    }

    private static String validCsv() {
        StringBuilder csv = new StringBuilder(
                "year,primary_energy_twh,accounting_basis,source_name,source_url,accessed_on\n");
        for (int index = 0; index < 10; index++) {
            csv.append(2015 + index).append(',')
                    .append(100000 + index).append(".0")
                    .append(",SUBSTITUTION,Reviewed source,")
                    .append("https://example.test/energy,2026-07-15\n");
        }
        return csv.toString();
    }
}
