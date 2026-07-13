package com.aienterprise.backend.tracker.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class HistoricalCorpusValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH = "a".repeat(64);

    private final HistoricalCorpusValidator validator = new HistoricalCorpusValidator();

    @Test
    void validReferenceOnlyCandidatePasses() {
        CorpusReport report = validator.validate(
                new ClassPathResource("tracker/backfill/historical-candidates-valid.jsonl"));

        assertEquals(1, report.totalCount());
        assertEquals(1, report.readyCount());
        assertEquals(0, report.rejectedCount());
        assertTrue(report.errors().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "quote", "evidenceQuote", "excerpt", "body", "html", "sourceTitle",
            "source_title", "Evidence-Quote"
    })
    void prohibitedSourceTextFieldsFailRecursively(String field) {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put(field, "copied source text");

        assertHasError(validate(candidate), "prohibited field");
    }

    @Test
    void duplicateCandidateIdFails() {
        ObjectNode candidate = validCandidate();

        assertHasError(validate(candidate, candidate.deepCopy()), "duplicate candidateId");
    }

    @Test
    void nonHttpsEvidenceUrlFails() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("url", "http://example.org/source");

        assertHasError(validate(candidate), "HTTPS URL required");
    }

    @Test
    void evidenceUrlOverOneThousandCharactersFails() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("url", "https://example.org/" + "x".repeat(982));

        assertHasError(validate(candidate), "URL exceeds 1000");
    }

    @Test
    void evidenceUrlCredentialsFail() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("url", "https://user:secret@example.org/source");

        assertHasError(validate(candidate), "URL credentials are prohibited");
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "api_key", "signature", "auth", "cookie", "password"})
    void sensitiveEvidenceUrlQueryFails(String parameter) {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("url", "https://example.org/source?" + parameter + "=secret");

        assertHasError(validate(candidate), "URL query contains sensitive parameter");
    }

    @Test
    void invalidLowercaseSha256Fails() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("contentSha256", "A".repeat(64));

        assertHasError(validate(candidate), "contentSha256");
    }

    @Test
    void blankLocatorFails() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("locator", "   ");

        assertHasError(validate(candidate), "locator must not be blank");
    }

    @Test
    void factSummaryOverFiveHundredCharactersFails() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("factSummary", "x".repeat(501));

        assertHasError(validate(candidate), "factSummary exceeds 500");
    }

    @Test
    void locatorOverThreeHundredCharactersFails() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("locator", "x".repeat(301));

        assertHasError(validate(candidate), "locator exceeds 300");
    }

    @Test
    void invalidOccurredOnDateFails() {
        ObjectNode candidate = validCandidate();
        candidate.put("occurredOn", "2015-02-30");

        assertHasError(validate(candidate), "invalid occurredOn");
    }

    @Test
    void invalidAccessedOnDateFails() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("accessedOn", "2026-02-30");

        assertHasError(validate(candidate), "invalid accessedOn");
    }

    @Test
    void emptyEvidenceFails() {
        ObjectNode candidate = validCandidate();
        candidate.set("evidence", JSON.createArrayNode());

        assertHasError(validate(candidate), "evidence must not be empty");
    }

    @Test
    void statusOutsideContractFails() {
        ObjectNode candidate = validCandidate();
        candidate.put("discoveryStatus", "APPROVED");

        assertHasError(validate(candidate), "invalid discoveryStatus");
    }

    @Test
    void publicationPathOutsideContractFails() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("publicationPath", "SOCIAL_MEDIA");

        assertHasError(validate(candidate), "invalid publicationPath");
    }

    @Test
    void rejectedCandidateRequiresDiscoveryNote() {
        ObjectNode candidate = validCandidate();
        candidate.put("discoveryStatus", "REJECTED");
        candidate.put("discoveryNote", " ");

        assertHasError(validate(candidate), "REJECTED candidate requires discoveryNote");
    }

    @Test
    void emptyCandidateTopicsFails() {
        ObjectNode candidate = validCandidate();
        candidate.set("candidateTopics", JSON.createArrayNode());

        assertHasError(validate(candidate), "candidateTopics must not be empty");
    }

    @Test
    void unknownCandidateFieldFailsClosed() {
        ObjectNode candidate = validCandidate();
        candidate.put("sourceProse", "unreviewed source content");

        assertHasError(validate(candidate), "unknown candidate field sourceProse");
    }

    @Test
    void unknownEvidenceFieldFailsClosed() {
        ObjectNode candidate = validCandidate();
        evidence(candidate).put("pageText", "unreviewed source content");

        assertHasError(validate(candidate), "unknown evidence field pageText");
    }

    @Test
    void independentlyAuthoredTextFieldsHaveStorageBounds() {
        ObjectNode candidate = validCandidate();
        candidate.put("eventTitle", "x".repeat(201));
        candidate.put("actor", "x".repeat(201));
        candidate.put("discoveryNote", "x".repeat(1001));

        CorpusReport report = validate(candidate);

        assertHasError(report, "eventTitle exceeds 200");
        assertHasError(report, "actor exceeds 200");
        assertHasError(report, "discoveryNote exceeds 1000");
    }

    @Test
    void candidateTopicsHaveCountAndLengthBounds() {
        ObjectNode candidate = validCandidate();
        ArrayNode topics = candidate.putArray("candidateTopics");
        for (int i = 0; i < 21; i++) {
            topics.add(i == 0 ? "x".repeat(101) : "topic-" + i);
        }

        CorpusReport report = validate(candidate);

        assertHasError(report, "candidateTopics exceeds 20 entries");
        assertHasError(report, "candidate topic exceeds 100");
    }

    @Test
    void evidenceCountIsBounded() {
        ObjectNode candidate = validCandidate();
        ArrayNode evidence = candidate.withArray("evidence");
        ObjectNode template = (ObjectNode) evidence.get(0);
        for (int i = 1; i < 9; i++) {
            ObjectNode copy = template.deepCopy();
            copy.put("sourceCode", "SOURCE" + i);
            evidence.add(copy);
        }

        assertHasError(validate(candidate), "evidence exceeds 8 entries");
    }

    @Test
    void jsonlLineOverEightKibibytesFails() {
        ObjectNode candidate = validCandidate();
        candidate.put("discoveryNote", "가".repeat(3000));

        assertHasError(validate(candidate), "line exceeds 8192 UTF-8 bytes");
    }

    private static ObjectNode validCandidate() {
        ObjectNode candidate = JSON.createObjectNode();
        candidate.put("candidateId", "HC-2015-F9-LANDING");
        candidate.put("eventTitle", "First-stage landing demonstration");
        candidate.putArray("candidateTopics").add("reusable launch").add("launch economics");
        candidate.put("actor", "SpaceX");
        candidate.put("occurredOn", "2015-12-21");
        ObjectNode evidence = candidate.putArray("evidence").addObject();
        evidence.put("sourceCode", "SPACEX");
        evidence.put("url", "https://example.org/mission-record");
        evidence.put("locator", "mission outcome section");
        evidence.put("accessedOn", "2026-07-13");
        evidence.put("contentSha256", HASH);
        evidence.put("publicationPath", "PRIMARY");
        evidence.put("factSummary", "The first stage landed after completing its flight segment.");
        candidate.put("discoveryStatus", "READY_FOR_MAPPING");
        candidate.put("discoveryNote", "Potential partial-reuse milestone.");
        return candidate;
    }

    private static ObjectNode evidence(ObjectNode candidate) {
        return (ObjectNode) candidate.withArray("evidence").get(0);
    }

    private CorpusReport validate(JsonNode... candidates) {
        StringBuilder jsonl = new StringBuilder();
        try {
            for (JsonNode candidate : candidates) {
                jsonl.append(JSON.writeValueAsString(candidate)).append('\n');
            }
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
        return validator.validate(new ByteArrayResource(
                jsonl.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertHasError(CorpusReport report, String expected) {
        assertTrue(report.errors().stream().anyMatch(error -> error.contains(expected)),
                () -> "Expected error containing '" + expected + "' but got " + report.errors());
    }
}
