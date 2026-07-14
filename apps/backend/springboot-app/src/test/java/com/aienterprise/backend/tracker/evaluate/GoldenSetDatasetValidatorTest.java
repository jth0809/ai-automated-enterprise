package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class GoldenSetDatasetValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TEST_REFS = Set.of("BF-P1-001");
    private static final Set<String> TEST_NODES = Set.of("P1-REUSE-LV");

    @Test
    void productionDatasetHasFiftyBalancedCopyrightSafeCases() throws IOException {
        Resource resource = new ClassPathResource("tracker/golden-set-v1.json");
        GoldenSetDatasetValidator.ValidationResult result =
                new GoldenSetDatasetValidator().validate(resource);

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals("golden-v1", result.datasetVersion());
        assertEquals("golden-output-v1", result.expectedSchemaVersion());
        assertEquals(50, result.cases().size());
        assertEquals(50, result.cases().stream().filter(GoldenSetDatasetValidator.GoldenCase::active).count());
        assertEquals(Set.of("SYNTHETIC", "HUMAN_PARAPHRASE"), result.cases().stream()
                .map(GoldenSetDatasetValidator.GoldenCase::fixtureKind)
                .collect(Collectors.toSet()));

        Set<String> pillars = result.cases().stream()
                .map(GoldenSetDatasetValidator.GoldenCase::expectedOutput)
                .filter(output -> output.path("relevant").asBoolean())
                .map(output -> output.path("nodeCode").asText().substring(0, 2))
                .collect(Collectors.toSet());
        assertEquals(Set.of("P1", "P2", "P3", "P4", "P5", "P6"), pillars);

        Set<String> tags = result.cases().stream()
                .flatMap(item -> item.scenarioTags().stream())
                .collect(Collectors.toSet());
        assertTrue(tags.containsAll(Set.of(
                "STATE", "NON_STATE", "ROLLBACK", "CANCELLATION", "ARRIVAL",
                "IRRELEVANT", "QUOTE_MISMATCH", "SCHEMA_ERROR", "LEVEL_BOUNDARY")));
        assertTrue(result.cases().stream()
                .filter(item -> "DUPLICATE".equals(item.pairExpectation())).count() >= 2);
        assertTrue(result.cases().stream()
                .filter(item -> "DISTINCT".equals(item.pairExpectation())).count() >= 2);
        assertTrue(result.cases().stream()
                .allMatch(item -> item.body().getBytes(StandardCharsets.UTF_8).length <= 2_000));
        assertTrue(result.cases().stream()
                .allMatch(item -> item.expectedOutputJson().getBytes(StandardCharsets.UTF_8).length <= 2_000));
        assertTrue(resource.contentLength() <= 256 * 1024);

        int maxBodyBytes = result.cases().stream()
                .mapToInt(item -> item.body().getBytes(StandardCharsets.UTF_8).length)
                .max().orElseThrow();
        int maxOutputBytes = result.cases().stream()
                .mapToInt(item -> item.expectedOutputJson().getBytes(StandardCharsets.UTF_8).length)
                .max().orElseThrow();
        System.out.printf(
                "GOLDEN_SET_REPORT cases=%d fileBytes=%d maxBodyBytes=%d maxOutputBytes=%d%n",
                result.cases().size(), resource.contentLength(), maxBodyBytes, maxOutputBytes);
    }

    @Test
    void rejectsForbiddenFieldsUrlsOversizeTextAndDuplicateCodes() {
        ObjectNode root = root(singleCase());
        ObjectNode first = (ObjectNode) root.withArray("cases").get(0);
        first.put("fixtureKind", "COPIED_ARTICLE");
        first.put("url", "https://example.test/article");
        first.put("body", "x".repeat(2_001));
        first.put("notes", "n".repeat(1_001));
        first.with("expectedOutput").put("sourceBody", "copied text");
        first.with("expectedOutput").put("evidenceQuote", "copied sentence");
        root.withArray("cases").add(first.deepCopy());

        GoldenSetDatasetValidator.ValidationResult result = validator(2)
                .validate(resource(root));

        assertHasError(result, "fixtureKind must be SYNTHETIC or HUMAN_PARAPHRASE");
        assertHasError(result, "forbidden field url");
        assertHasError(result, "URL-like content is forbidden");
        assertHasError(result, "body exceeds 2000 UTF-8 bytes");
        assertHasError(result, "notes exceeds 1000 characters");
        assertHasError(result, "forbidden field sourceBody");
        assertHasError(result, "forbidden field evidenceQuote");
        assertHasError(result, "duplicate caseCode GOLD-TEST-001");
    }

    @Test
    void rejectsInvalidExpectedSchemaNodeLevelAndProvenance() {
        ObjectNode item = singleCase();
        item.putArray("provenanceRefs").add("BF-NOT-APPROVED");
        ObjectNode expected = item.with("expectedOutput");
        expected.put("nodeCode", "P9-UNKNOWN");
        expected.put("claimedLevel", 10);
        expected.put("extraNumber", 4);
        ObjectNode root = root(item);
        root.put("expectedSchemaVersion", "unknown-output-v9");

        GoldenSetDatasetValidator.ValidationResult result = validator(1)
                .validate(resource(root));

        assertHasError(result, "expectedSchemaVersion must be golden-output-v1");
        assertHasError(result, "unknown provenance ref BF-NOT-APPROVED");
        assertHasError(result, "unknown nodeCode P9-UNKNOWN");
        assertHasError(result, "claimedLevel must be between 1 and 9");
        assertHasError(result, "expectedOutput fields must be exactly");
    }

    @Test
    void rejectsOversizeFixtureBeforeParsing() {
        GoldenSetDatasetValidator.ValidationResult result = validator(1).validate(
                new ByteArrayResource("x".repeat(256 * 1024 + 1)
                        .getBytes(StandardCharsets.UTF_8)));

        assertHasError(result, "file exceeds 262144 bytes");
    }

    @Test
    void irrelevantCasesRequireNullSemanticFields() {
        ObjectNode item = singleCase();
        ObjectNode expected = item.with("expectedOutput");
        expected.put("relevant", false);

        GoldenSetDatasetValidator.ValidationResult result = validator(1)
                .validate(resource(root(item)));

        assertHasError(result, "irrelevant expectedOutput requires null semantic fields");
    }

    private GoldenSetDatasetValidator validator(int expectedCases) {
        return new GoldenSetDatasetValidator(TEST_REFS, TEST_NODES, expectedCases);
    }

    private ObjectNode root(ObjectNode... cases) {
        ObjectNode root = JSON.createObjectNode();
        root.put("datasetVersion", "golden-test-v1");
        root.put("nodeSetVersion", "nodes-v1.0");
        root.put("rubricVersion", "r2.0");
        root.put("expectedSchemaVersion", "golden-output-v1");
        ArrayNode array = root.putArray("cases");
        for (ObjectNode item : cases) {
            array.add(item);
        }
        return root;
    }

    private ObjectNode singleCase() {
        ObjectNode item = JSON.createObjectNode();
        item.put("caseCode", "GOLD-TEST-001");
        item.put("fixtureKind", "SYNTHETIC");
        item.put("title", "Reusable stage test completes");
        item.put("body", "The authored test stage flew and was recovered after landing.");
        item.put("active", true);
        item.putArray("provenanceRefs").add("BF-P1-001");
        item.putArray("scenarioTags").add("P1").add("STATE");
        item.putNull("pairCode");
        item.putNull("pairExpectation");
        item.put("notes", "Synthetic boundary case.");
        ObjectNode output = item.putObject("expectedOutput");
        output.put("relevant", true);
        output.put("nodeCode", "P1-REUSE-LV");
        output.put("eventType", "FLIGHT_TEST");
        output.put("claimedLevel", 5);
        output.put("actor", "Test operator");
        output.put("occurredOn", "2026-01-02");
        output.put("publicationPath", "PRIMARY");
        return item;
    }

    private Resource resource(ObjectNode root) {
        try {
            return new ByteArrayResource(JSON.writeValueAsBytes(root));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertHasError(
            GoldenSetDatasetValidator.ValidationResult result, String expected) {
        assertTrue(result.errors().stream().anyMatch(error -> error.contains(expected)),
                () -> "missing error '" + expected + "' in " + result.errors());
    }
}
