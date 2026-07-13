package com.aienterprise.backend.tracker.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class BackfillDatasetValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH = "a".repeat(64);

    @Test
    void productionMappingResolvesCandidateEvidenceAndVersions() {
        ValidatedBackfill result = new BackfillDatasetValidator().validate(
                new ClassPathResource("tracker/historical-candidates-v1.jsonl"),
                new ClassPathResource("tracker/backfill-v1.json"));

        assertEquals(140, result.claims().size());
        assertEquals(136, result.candidates().size());
        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
    }

    @Test
    void productionModeRejectsAnythingOtherThanTheApprovedCardinalities() {
        ObjectNode candidate = candidate("HC-VALID", evidence("NASA", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "NASA");

        ValidatedBackfill result = new BackfillDatasetValidator(true)
                .validate(candidates(candidate), mappings(mapping));

        assertHasError(result, "production corpus must contain exactly 210 candidates");
        assertHasError(result, "production corpus must contain exactly 210 READY candidates");
        assertHasError(result, "production mapping must contain 110-150 claims");
    }

    @Test
    void unknownCandidateFailsClosed() {
        BackfillDatasetValidator validator = validator(catalog(source("AGENCY", 1, "AGENCY")));
        ObjectNode mapping = mapping("BF-1", "HC-MISSING", "OFFICIAL", "AGENCY");

        assertHasError(validator.validate(candidates(candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1))),
                mappings(mapping)), "unknown candidate HC-MISSING");
    }

    @Test
    void unknownEvidenceReferenceFailsClosed() {
        BackfillDatasetValidator validator = validator(catalog(source("AGENCY", 1, "AGENCY")));
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "MISSING");

        assertHasError(validator.validate(candidates(candidate), mappings(mapping)),
                "unknown evidence ref HC-VALID#MISSING");
    }

    @Test
    void unknownNodeFailsClosed() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "AGENCY");
        mapping.put("nodeCode", "P9-NOT-REGISTERED");

        assertHasError(validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(mapping)), "unknown nodeCode");
    }

    @Test
    void duplicateBackfillIdFailsClosed() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode first = mapping("BF-DUP", "HC-VALID", "OFFICIAL", "AGENCY");
        ObjectNode second = first.deepCopy();

        assertHasError(validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(first, second)), "duplicate backfillId BF-DUP");
    }

    @Test
    void invalidEventTypeLevelAndDateFailClosed() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode invalidType = mapping("BF-TYPE", "HC-VALID", "OFFICIAL", "AGENCY");
        invalidType.put("eventType", "MARKETING");
        ObjectNode invalidLevel = mapping("BF-LEVEL", "HC-VALID", "OFFICIAL", "AGENCY");
        invalidLevel.put("claimedLevel", 10);
        ObjectNode invalidDate = mapping("BF-DATE", "HC-VALID", "OFFICIAL", "AGENCY");
        invalidDate.put("occurredOn", "2026-02-30");

        ValidatedBackfill result = validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(invalidType, invalidLevel, invalidDate));

        assertHasError(result, "invalid eventType MARKETING");
        assertHasError(result, "claimedLevel must be null or between 1 and 9");
        assertHasError(result, "invalid occurredOn");
    }

    @Test
    void nonProgressEventMustHaveNullLevel() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "AGENCY");
        mapping.put("eventType", "SETBACK");
        mapping.put("claimedLevel", 3);

        assertHasError(validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(mapping)),
                "SETBACK requires null claimedLevel");
    }

    @Test
    void bothReviewsMustBeApproved() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "AGENCY");
        ((ObjectNode) mapping.get("review")).put("rubric", "REJECTED");

        assertHasError(validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(mapping)),
                "fact and rubric reviews must both be APPROVED");
    }

    @Test
    void nodeAndRubricVersionsMustMatchApprovedVersions() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "AGENCY");
        mapping.put("nodeSetVersion", "nodes-v0.1");
        mapping.put("rubricVersion", "r1.0");

        ValidatedBackfill result = validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(mapping));

        assertHasError(result, "nodeSetVersion must be nodes-v1.0");
        assertHasError(result, "rubricVersion must be r2.0");
    }

    @Test
    void mappedFactIdentityMustMatchTheCandidate() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "AGENCY");
        mapping.put("eventTitle", "A different event");

        assertHasError(validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(mapping)),
                "eventTitle does not match candidate");
    }

    @Test
    void missingEvidenceFailsClosed() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "AGENCY");
        mapping.set("evidenceRefs", JSON.createArrayNode());

        assertHasError(validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(mapping)),
                "evidenceRefs must not be empty");
    }

    @Test
    void prohibitedSourceTextKeysFailRecursively() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "OFFICIAL", "AGENCY");
        ((ObjectNode) mapping.get("review")).put("evidenceQuote", "copied source text");

        assertHasError(validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(mapping)), "prohibited field");
    }

    @Test
    void agencyPrimaryDerivesOfficial() {
        assertVerification("OFFICIAL",
                catalog(source("AGENCY", 1, "AGENCY")),
                evidence("AGENCY", "PRIMARY", 1));
    }

    @Test
    void journalDerivesPeerReviewed() {
        assertVerification("PEER_REVIEWED",
                catalog(source("JOURNAL", 1, "JOURNAL")),
                evidence("JOURNAL", "PRIMARY", 1));
    }

    @Test
    void twoDistinctReliableNonWireSourcesDeriveIndependent() {
        assertVerification("INDEPENDENT",
                catalog(source("NEWS1", 1, "MEDIA"), source("NEWS2", 2, "MEDIA")),
                evidence("NEWS1", "PRIMARY", 1), evidence("NEWS2", "THIRD_PARTY", 2));
    }

    @Test
    void duplicateSourceAndWireReprintDoNotDeriveIndependent() {
        Resource sourceCatalog = catalog(
                source("NEWS1", 1, "MEDIA"), source("WIRE", 1, "MEDIA"));
        ObjectNode candidate = candidate("HC-VALID",
                evidence("NEWS1", "PRIMARY", 1),
                evidence("NEWS1", "THIRD_PARTY", 2),
                evidence("WIRE", "WIRE_REPRINT", 3));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "CLAIMED", "NEWS1", "WIRE");

        ValidatedBackfill result = validator(sourceCatalog)
                .validate(candidates(candidate), mappings(mapping));

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals("CLAIMED", result.claims().getFirst().expectedVerificationLevel());
    }

    @Test
    void expectedVerificationMismatchFailsClosed() {
        ObjectNode candidate = candidate("HC-VALID", evidence("AGENCY", "PRIMARY", 1));
        ObjectNode mapping = mapping("BF-1", "HC-VALID", "INDEPENDENT", "AGENCY");

        assertHasError(validator(catalog(source("AGENCY", 1, "AGENCY")))
                .validate(candidates(candidate), mappings(mapping)),
                "expectedVerificationLevel mismatch: expected INDEPENDENT but derived OFFICIAL");
    }

    private static void assertVerification(
            String expected, Resource sourceCatalog, ObjectNode... evidence) {
        ObjectNode candidate = candidate("HC-VALID", evidence);
        String[] refs = java.util.Arrays.stream(evidence)
                .map(item -> item.get("sourceCode").asText())
                .toArray(String[]::new);
        ObjectNode mapping = mapping("BF-1", "HC-VALID", expected, refs);

        ValidatedBackfill result = validator(sourceCatalog)
                .validate(candidates(candidate), mappings(mapping));

        assertFalse(result.claims().isEmpty());
        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals(expected, result.claims().getFirst().expectedVerificationLevel());
    }

    private static BackfillDatasetValidator validator(Resource sourceCatalog) {
        return new BackfillDatasetValidator(sourceCatalog);
    }

    private static ObjectNode source(String code, int tier, String sourceType) {
        ObjectNode source = JSON.createObjectNode();
        source.put("sourceCode", code);
        source.put("name", code + " source");
        source.put("domain", code.toLowerCase() + ".example");
        source.put("sourceType", sourceType);
        source.put("tier", tier);
        source.put("feedActive", false);
        return source;
    }

    private static Resource catalog(ObjectNode... sources) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode source : sources) {
            array.add(source);
        }
        return resource(toJson(array));
    }

    private static ObjectNode candidate(String candidateId, ObjectNode... evidence) {
        ObjectNode candidate = JSON.createObjectNode();
        candidate.put("candidateId", candidateId);
        candidate.put("eventTitle", "Reviewed event");
        candidate.putArray("candidateTopics").add("topic");
        candidate.put("actor", "Reviewed actor");
        candidate.put("occurredOn", "2026-01-02");
        candidate.put("occurredOnPrecision", "DAY");
        ArrayNode evidenceArray = candidate.putArray("evidence");
        for (ObjectNode item : evidence) {
            evidenceArray.add(item);
        }
        candidate.put("discoveryStatus", "READY_FOR_MAPPING");
        candidate.put("discoveryNote", "Boundary checked.");
        return candidate;
    }

    private static ObjectNode evidence(String sourceCode, String publicationPath, int sequence) {
        ObjectNode evidence = JSON.createObjectNode();
        evidence.put("sourceCode", sourceCode);
        evidence.put("url", "https://" + sourceCode.toLowerCase()
                + ".example/reference-" + sequence);
        evidence.put("locator", "official section " + sequence);
        evidence.put("accessedOn", "2026-07-13");
        evidence.put("contentSha256", HASH);
        evidence.put("publicationPath", publicationPath);
        evidence.put("factSummary", "Reviewer-authored factual summary " + sequence + ".");
        return evidence;
    }

    private static ObjectNode mapping(
            String backfillId,
            String candidateId,
            String expectedVerification,
            String... sourceCodes) {
        ObjectNode mapping = JSON.createObjectNode();
        mapping.put("backfillId", backfillId);
        mapping.put("candidateId", candidateId);
        mapping.put("nodeSetVersion", "nodes-v1.0");
        mapping.put("rubricVersion", "r2.0");
        mapping.put("nodeCode", "P1-ORBIT-REFUEL");
        mapping.put("eventType", "FLIGHT_TEST");
        mapping.put("claimedLevel", 5);
        mapping.put("actor", "Reviewed actor");
        mapping.put("occurredOn", "2026-01-02");
        mapping.put("occurredOnPrecision", "DAY");
        mapping.put("expectedVerificationLevel", expectedVerification);
        mapping.put("eventTitle", "Reviewed event");
        mapping.put("rubricJustification", "Boundary checked.");
        ArrayNode refs = mapping.putArray("evidenceRefs");
        for (String sourceCode : sourceCodes) {
            refs.add(candidateId + "#" + sourceCode);
        }
        ObjectNode review = mapping.putObject("review");
        review.put("fact", "APPROVED");
        review.put("rubric", "APPROVED");
        review.put("reviewerNote", "Fact and rubric checked separately.");
        return mapping;
    }

    private static Resource candidates(ObjectNode... candidates) {
        StringBuilder jsonl = new StringBuilder();
        for (ObjectNode candidate : candidates) {
            jsonl.append(toJson(candidate)).append('\n');
        }
        return resource(jsonl.toString());
    }

    private static Resource mappings(ObjectNode... mappings) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode mapping : mappings) {
            array.add(mapping);
        }
        return resource(toJson(array));
    }

    private static Resource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String toJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertHasError(ValidatedBackfill result, String expected) {
        assertTrue(result.errors().stream().anyMatch(error -> error.contains(expected)),
                () -> "Expected error containing '" + expected + "' but got " + result.errors());
    }
}
