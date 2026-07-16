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

import com.aienterprise.backend.tracker.governance.GovernanceLedgerValidatorTest;
import com.aienterprise.backend.tracker.governance.GovernanceRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class GovernanceLedgerLoaderTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private GovernanceRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void firstImportAndSameHashAreIdempotent() {
        GovernanceLedgerLoader loader = loader(validJson(), "governance-test-v1");

        loader.loadIfNeeded();
        loader.loadIfNeeded();

        assertEquals(6, repository.findAll(50).size());
        assertEquals(1, importCount());
    }

    @Test
    void changedHashForSameVersionFailsWithoutMutation() {
        loader(validJson(), "governance-test-v1").loadIfNeeded();

        assertThrows(IllegalStateException.class, () -> loader(
                validJson().replace("subject 0", "subject revised 0"),
                "governance-test-v1").loadIfNeeded());

        assertEquals("Reviewed governance subject 0",
                repository.findAll(50).stream()
                        .filter(record -> record.recordId().equals("REC-0"))
                        .findFirst().orElseThrow().subject());
        assertEquals(1, importCount());
    }

    @Test
    void newVersionCanUpsertReviewedFacts() {
        loader(validJson(), "governance-test-v1").loadIfNeeded();
        loader(validJson().replace("subject 0", "subject revised 0"),
                "governance-test-v2").loadIfNeeded();

        assertEquals(6, repository.findAll(50).size());
        assertEquals("Reviewed governance subject revised 0",
                repository.findAll(50).stream()
                        .filter(record -> record.recordId().equals("REC-0"))
                        .findFirst().orElseThrow().subject());
        assertEquals(2, importCount());
    }

    @Test
    void invalidDatasetWritesNothing() {
        String invalid = validJson().replaceFirst("https://www.unoosa.org",
                "http://attacker.example");

        assertThrows(IllegalStateException.class,
                () -> loader(invalid, "governance-test-v1").loadIfNeeded());

        assertEquals(0, repository.findAll(50).size());
        assertEquals(0, importCount());
    }

    private GovernanceLedgerLoader loader(String json, String version) {
        return new GovernanceLedgerLoader(
                repository,
                new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)),
                version,
                CLOCK);
    }

    private int importCount() {
        return jdbc.sql("SELECT COUNT(*) FROM governance_import")
                .query(Integer.class).single();
    }

    private static String validJson() {
        return GovernanceLedgerValidatorTest.validDataset(6);
    }
}
