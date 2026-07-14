package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"tracker.enabled=true", "tracker.backfill-on-boot=false"})
@ActiveProfiles("test")
class GoldenSetLoaderTest {

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM golden_set_result").update();
        jdbc.sql("DELETE FROM golden_set_run").update();
        jdbc.sql("DELETE FROM golden_set_item").update();
        jdbc.sql("DELETE FROM golden_set_dataset").update();
    }

    @Test
    void loadsProductionDatasetOnceAndPreservesCanonicalHash() {
        GoldenSetLoader loader = new GoldenSetLoader(
                jdbc,
                new ClassPathResource("tracker/golden-set-v1.json"),
                new GoldenSetDatasetValidator());

        GoldenSetLoader.LoadSummary first = loader.loadIfNeeded();
        GoldenSetLoader.LoadSummary second = loader.loadIfNeeded();

        assertEquals(50, first.insertedItems());
        assertTrue(!first.noOp());
        assertEquals(0, second.insertedItems());
        assertTrue(second.noOp());
        assertEquals(50, jdbc.sql("SELECT COUNT(*) FROM golden_set_item")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM golden_set_dataset")
                .query(Integer.class).single());
        assertEquals(first.datasetSha256(), jdbc.sql("""
                SELECT dataset_sha256 FROM golden_set_dataset
                 WHERE dataset_version = 'golden-v1'
                """).query(String.class).single().trim());
        assertEquals(50, jdbc.sql("""
                SELECT COUNT(*) FROM golden_set_item
                 WHERE fixture_kind IN ('SYNTHETIC','HUMAN_PARAPHRASE')
                   AND active = 'Y'
                """).query(Integer.class).single());
    }

    @Test
    void sameVersionWithDifferentCanonicalContentFailsClosed() {
        GoldenSetDatasetValidator validator = new GoldenSetDatasetValidator(
                Set.of("BF-P1-001"), Set.of("P1-REUSE-LV"), 1);
        GoldenSetLoader first = new GoldenSetLoader(
                jdbc, fixture("First authored body."), validator);
        GoldenSetLoader changed = new GoldenSetLoader(
                jdbc, fixture("Changed authored body."), validator);

        first.loadIfNeeded();

        IllegalStateException error = assertThrows(
                IllegalStateException.class, changed::loadIfNeeded);
        assertTrue(error.getMessage().contains("dataset hash mismatch"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM golden_set_item")
                .query(Integer.class).single());
    }

    private ByteArrayResource fixture(String body) {
        String escaped = body.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = """
                {
                  "datasetVersion":"golden-test-v1",
                  "nodeSetVersion":"nodes-v1.0",
                  "rubricVersion":"r2.0",
                  "expectedSchemaVersion":"golden-output-v1",
                  "cases":[{
                    "caseCode":"GOLD-TEST-001",
                    "fixtureKind":"SYNTHETIC",
                    "title":"Authored fixture",
                    "body":"%s",
                    "expectedOutput":{
                      "relevant":true,
                      "nodeCode":"P1-REUSE-LV",
                      "eventType":"FLIGHT_TEST",
                      "claimedLevel":5,
                      "actor":"Test operator",
                      "occurredOn":"2026-01-02",
                      "publicationPath":"PRIMARY"
                    },
                    "notes":"Synthetic test only.",
                    "provenanceRefs":["BF-P1-001"],
                    "scenarioTags":["P1","STATE"],
                    "pairCode":null,
                    "pairExpectation":null,
                    "active":true
                  }]
                }
                """.formatted(escaped);
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }
}
