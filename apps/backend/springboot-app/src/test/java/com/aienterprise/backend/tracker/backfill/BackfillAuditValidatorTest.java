package com.aienterprise.backend.tracker.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class BackfillAuditValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock AS_OF = Clock.fixed(
            Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void productionAuditCoversAllNodesAndMatchesReplay() {
        ValidatedBackfill backfill = new BackfillDatasetValidator(true).validate(
                new ClassPathResource("tracker/historical-candidates-v1.jsonl"),
                new ClassPathResource("tracker/backfill-v1.json"));
        assertTrue(backfill.errors().isEmpty(), () -> String.join("\n", backfill.errors()));

        ValidatedNodeAudit result = new BackfillAuditValidator(AS_OF).validate(
                new ClassPathResource("tracker/backfill-audit-v1.json"), backfill);

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals(35, result.entries().size());
        assertEquals(2, result.entries().stream()
                .filter(entry -> "DORMANT".equals(entry.status()))
                .count());
        assertEquals(2, result.entries().stream()
                .filter(entry -> entry.auditedLevel() == 0)
                .count());
    }

    @Test
    void levelClaimsAndEvidenceMustDirectlySupportAuditedLevel() {
        BackfillClaim claim = claim("BF-TEST-1", 3, List.of("HC-TEST#NASA"));
        ObjectNode entry = entry("P2-HEALTH-AUTONOMY", 5);
        entry.putArray("levelClaimIds").add("BF-TEST-1");
        entry.putArray("levelEvidenceRefs").add("HC-OTHER#NASA");

        ValidatedNodeAudit result = validator("P2-HEALTH-AUTONOMY")
                .validate(resource(array(entry)), backfill(claim));

        assertHasError(result, "does not support audited level 5");
        assertHasError(result, "levelEvidenceRefs must exactly match");
    }

    @Test
    void levelZeroMustRemainAnHonestEmptyEvidenceState() {
        ObjectNode entry = entry("P4-RESOURCE-INTEGRATION", 0);
        entry.putArray("levelClaimIds").add("BF-TEST-1");
        entry.putArray("levelEvidenceRefs").add("HC-TEST#NASA");

        ValidatedNodeAudit result = validator("P4-RESOURCE-INTEGRATION")
                .validate(resource(array(entry)), backfill());

        assertHasError(result, "level 0 requires empty levelClaimIds and levelEvidenceRefs");
    }

    @Test
    void auditRequiresEveryExpectedNodeAndIndependentReviewFocuses() {
        ObjectNode entry = entry("P2-HEALTH-AUTONOMY", 0);
        ArrayNode reviews = (ArrayNode) entry.get("reviews");
        reviews.removeAll();
        reviews.add(review("same-reviewer", "FACT"));
        reviews.add(review("same-reviewer", "FACT"));

        ValidatedNodeAudit result = new BackfillAuditValidator(
                AS_OF, Set.of("P2-HEALTH-AUTONOMY", "P3-THERMAL"))
                .validate(resource(array(entry)), backfill());

        assertHasError(result, "reviews require distinct reviewerId values");
        assertHasError(result, "reviews must cover FACT and RUBRIC exactly once");
        assertHasError(result, "missing nodeCode P3-THERMAL");
    }

    private static BackfillAuditValidator validator(String nodeCode) {
        return new BackfillAuditValidator(AS_OF, Set.of(nodeCode));
    }

    private static BackfillClaim claim(
            String backfillId, Integer level, List<String> evidenceRefs) {
        return new BackfillClaim(
                backfillId, "HC-TEST", "nodes-v1.0", "r2.0",
                "P2-HEALTH-AUTONOMY", "FLIGHT_TEST", level, "NASA",
                LocalDate.of(2020, 4, 9), "DAY", "OFFICIAL", "Test event",
                "Boundary checked.", ProgramEndEffect.NONE, null, evidenceRefs,
                new BackfillReview("APPROVED", "APPROVED", "Checked separately."));
    }

    private static ValidatedBackfill backfill(BackfillClaim... claims) {
        return new ValidatedBackfill(List.of(claims), Map.of(), List.of());
    }

    private static ObjectNode entry(String nodeCode, int level) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("nodeCode", nodeCode);
        entry.put("nodeSetVersion", "nodes-v1.0");
        entry.put("rubricVersion", "r2.0");
        entry.put("auditedOn", "2026-07-14");
        entry.put("auditedLevel", level);
        entry.put("status", "ACTIVE");
        entry.putArray("levelClaimIds");
        entry.putArray("levelEvidenceRefs");
        entry.putArray("statusClaimIds");
        entry.put("nextLevelGap", "The next directly observed boundary is not met.");
        entry.put("statusRationale", "No capability-wide program end is recorded.");
        ArrayNode reviews = entry.putArray("reviews");
        reviews.add(review("phase2-fact-audit", "FACT"));
        reviews.add(review("phase2-rubric-audit", "RUBRIC"));
        return entry;
    }

    private static ObjectNode review(String reviewerId, String focus) {
        ObjectNode review = JSON.createObjectNode();
        review.put("reviewerId", reviewerId);
        review.put("focus", focus);
        review.put("decision", "APPROVED");
        return review;
    }

    private static ArrayNode array(ObjectNode... entries) {
        ArrayNode result = JSON.createArrayNode();
        for (ObjectNode entry : entries) {
            result.add(entry);
        }
        return result;
    }

    private static Resource resource(ArrayNode content) {
        try {
            return new ByteArrayResource(
                    JSON.writeValueAsBytes(content));
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private static Resource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String array(ObjectNode entry) {
        try {
            return JSON.writeValueAsString(JSON.createArrayNode().add(entry));
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertHasError(ValidatedNodeAudit result, String expected) {
        assertTrue(result.errors().stream().anyMatch(error -> error.contains(expected)),
                () -> "Expected error containing '" + expected + "' but got " + result.errors());
    }
}
