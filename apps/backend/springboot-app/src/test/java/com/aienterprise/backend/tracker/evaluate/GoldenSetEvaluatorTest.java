package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"tracker.enabled=true", "tracker.backfill-on-boot=false"})
@ActiveProfiles("test")
class GoldenSetEvaluatorTest {

    @Autowired
    private TrackerRepository repository;

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
    void agreementUsesAllCasesAndPreservesRunModeAndHashes() {
        GoldenSetEvaluator.Report fortyFive = evaluator(mismatchClassifier(5))
                .evaluate(GoldenSetEvaluator.RunMode.OFFLINE_REPLAY, versions("offline-a"));
        GoldenSetEvaluator.Report fortyFour = evaluator(mismatchClassifier(6))
                .evaluate(GoldenSetEvaluator.RunMode.DRILL, versions("drill-a"));

        assertEquals(50, fortyFive.totalCount());
        assertEquals(45, fortyFive.matchedCount());
        assertEquals(0.90, fortyFive.agreement(), 1e-12);
        assertEquals("SUCCEEDED", fortyFive.status());
        assertEquals(GoldenSetEvaluator.RunMode.OFFLINE_REPLAY, fortyFive.mode());
        assertEquals(44, fortyFour.matchedCount());
        assertEquals(0.88, fortyFour.agreement(), 1e-12);
        assertEquals(GoldenSetEvaluator.RunMode.DRILL, fortyFour.mode());

        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM golden_set_run")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM golden_set_run
                 WHERE mode = 'OFFLINE_REPLAY' AND total_count = 50
                   AND matched_count = 45 AND agreement = 0.90
                """).query(Integer.class).single());
        assertEquals(100, jdbc.sql("SELECT COUNT(*) FROM golden_set_result")
                .query(Integer.class).single());
        assertEquals(100, jdbc.sql("""
                SELECT COUNT(*) FROM golden_set_result
                 WHERE LENGTH(actual_output_sha256) = 64
                """).query(Integer.class).single());
    }

    @Test
    void classifierExceptionDoesNotStopRemainingCasesAndStoresNoRawResponse() {
        Map<String, GoldenOutput> expected = expectedOutputs();
        AtomicInteger calls = new AtomicInteger();
        GoldenClassifier classifier = input -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                throw new IllegalStateException("secret raw model response must not persist");
            }
            return withFixtureQuote(expected.get(input.caseCode()), input);
        };

        GoldenSetEvaluator.Report report = evaluator(classifier)
                .evaluate(GoldenSetEvaluator.RunMode.OFFLINE_REPLAY, versions("offline-error"));

        assertEquals(50, calls.get());
        assertEquals(50, report.totalCount());
        assertEquals(49, report.matchedCount());
        assertEquals(1, report.failedCount());
        assertEquals("FAILED", report.status());
        assertEquals(50, jdbc.sql("SELECT COUNT(*) FROM golden_set_result")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM golden_set_result
                 WHERE error_code = 'CLASSIFIER_ERROR'
                   AND actual_output_sha256 IS NULL
                   AND mismatch_fields IS NULL
                """).query(Integer.class).single());
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM golden_set_result
                 WHERE error_code LIKE '%secret%' OR mismatch_fields LIKE '%secret%'
                """).query(Integer.class).single());
    }

    @Test
    void quoteAndSchemaErrorsAreBoundedCaseFailures() {
        Map<String, GoldenOutput> expected = expectedOutputs();
        GoldenClassifier classifier = input -> {
            GoldenOutput output = expected.get(input.caseCode());
            if ("GOLD-044".equals(input.caseCode())) {
                return withEvidenceQuote(output, "text absent from authored fixture");
            }
            if ("GOLD-045".equals(input.caseCode())) {
                return new GoldenOutput(
                        true, output.nodeCode(), output.eventType(), output.claimedLevel(),
                        null, output.occurredOn(), output.publicationPath(), input.body());
            }
            return withFixtureQuote(output, input);
        };

        GoldenSetEvaluator.Report report = evaluator(classifier)
                .evaluate(GoldenSetEvaluator.RunMode.DRILL, versions("drill-errors"));

        assertEquals(48, report.matchedCount());
        assertEquals(2, report.failedCount());
        assertEquals("FAILED", report.status());
        assertEquals(1, errorCount("QUOTE_MISMATCH"));
        assertEquals(1, errorCount("SCHEMA_INVALID"));
    }

    @Test
    void eachSemanticMismatchFieldIsPersistedWithoutActualPayload() {
        Map<String, GoldenOutput> expected = expectedOutputs();
        AtomicInteger calls = new AtomicInteger();
        GoldenClassifier classifier = input -> {
            GoldenOutput output = expected.get(input.caseCode());
            if (calls.getAndIncrement() == 0) {
                return new GoldenOutput(
                        false, null, null, null, null, null, null, null);
            }
            return withFixtureQuote(output, input);
        };

        GoldenSetEvaluator.Report report = evaluator(classifier)
                .evaluate(GoldenSetEvaluator.RunMode.OFFLINE_REPLAY, versions("offline-diff"));

        assertEquals(49, report.matchedCount());
        String fields = jdbc.sql("""
                SELECT mismatch_fields FROM golden_set_result
                 WHERE matched = 'N' AND error_code IS NULL
                """).query(String.class).single();
        assertTrue(fields.contains("relevant"));
        assertTrue(fields.contains("nodeCode"));
        assertTrue(fields.contains("eventType"));
        assertTrue(fields.contains("claimedLevel"));
        assertTrue(fields.contains("actor"));
        assertTrue(fields.contains("occurredOn"));
        assertTrue(fields.contains("publicationPath"));
        assertTrue(fields.length() < 200);
    }

    private GoldenSetEvaluator evaluator(GoldenClassifier classifier) {
        GoldenSetLoader loader = new GoldenSetLoader(
                jdbc,
                new ClassPathResource("tracker/golden-set-v1.json"),
                new GoldenSetDatasetValidator());
        return new GoldenSetEvaluator(repository, loader, classifier);
    }

    private GoldenSetEvaluator.VersionTuple versions(String modelVersion) {
        return new GoldenSetEvaluator.VersionTuple(
                "golden-v1", "prompt-v1", modelVersion,
                "r2.0", "golden-output-v1");
    }

    private GoldenClassifier mismatchClassifier(int mismatchCount) {
        Map<String, GoldenOutput> expected = expectedOutputs();
        AtomicInteger calls = new AtomicInteger();
        return input -> {
            GoldenOutput output = expected.get(input.caseCode());
            if (calls.getAndIncrement() < mismatchCount) {
                return new GoldenOutput(
                        output.relevant(), output.nodeCode(), output.eventType(),
                        output.claimedLevel(), output.actor() + " changed",
                        output.occurredOn(), output.publicationPath(), input.body());
            }
            return withFixtureQuote(output, input);
        };
    }

    private Map<String, GoldenOutput> expectedOutputs() {
        GoldenSetDatasetValidator.ValidationResult dataset =
                new GoldenSetDatasetValidator().validate(
                        new ClassPathResource("tracker/golden-set-v1.json"));
        assertTrue(dataset.valid(), () -> String.join("\n", dataset.errors()));
        Map<String, GoldenOutput> result = new HashMap<>();
        dataset.cases().forEach(item -> result.put(
                item.caseCode(), GoldenOutput.fromExpectedJson(item.expectedOutputJson())));
        return result;
    }

    private static GoldenOutput withFixtureQuote(GoldenOutput output, GoldenInput input) {
        return output.relevant() ? withEvidenceQuote(output, input.body()) : output;
    }

    private static GoldenOutput withEvidenceQuote(GoldenOutput output, String quote) {
        return new GoldenOutput(
                output.relevant(), output.nodeCode(), output.eventType(),
                output.claimedLevel(), output.actor(), output.occurredOn(),
                output.publicationPath(), quote);
    }

    private int errorCount(String errorCode) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM golden_set_result WHERE error_code = :errorCode
                """).param("errorCode", errorCode).query(Integer.class).single();
    }
}
