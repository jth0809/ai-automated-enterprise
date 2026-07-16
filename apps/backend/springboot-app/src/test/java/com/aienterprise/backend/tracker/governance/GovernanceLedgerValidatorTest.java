package com.aienterprise.backend.tracker.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

public class GovernanceLedgerValidatorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
    private final GovernanceLedgerValidator validator = new GovernanceLedgerValidator();

    @Test
    void acceptsFlexibleReviewedDatasetsWithoutExactCountPin() {
        assertEquals(6, validate(validDataset(6)).records().size());
        assertEquals(9, validate(validDataset(9)).records().size());
    }

    @Test
    void rejectsTooFewOrTooManyRecords() {
        assertContains(validate(validDataset(5)), "between 6 and 100");
        assertContains(validate(validDataset(101)), "between 6 and 100");
    }

    @Test
    void rejectsUnknownAndContentOrScoringKeysAtAnyDepth() {
        String unknown = validDataset(6).replaceFirst("\\{", "{\"unexpected\":true,");
        String nestedBody = validDataset(6).replaceFirst(
                "\"status\":\"PUBLISHED\"",
                "\"status\":\"PUBLISHED\",\"metadata\":{\"body\":\"source text\"}");
        String score = validDataset(6).replaceFirst(
                "\"status\":\"PUBLISHED\"",
                "\"status\":\"PUBLISHED\",\"score\":7");

        assertContains(validate(unknown), "unknown key unexpected");
        assertContains(validate(nestedBody), "prohibited key body");
        assertContains(validate(score), "prohibited key score");
    }

    @Test
    void rejectsDuplicateIdsUnsafeHostsAndMalformedHashes() {
        String duplicate = validDataset(6).replace("REC-2", "REC-1");
        String unsafe = validDataset(6).replaceFirst(
                "https://www.unoosa.org", "https://attacker.example");
        String badHash = validDataset(6).replaceFirst("[0-9a-f]{64}", "ABC123");

        assertContains(validate(duplicate), "duplicate recordId REC-1");
        assertContains(validate(unsafe), "official HTTPS host");
        assertContains(validate(badHash), "64 lowercase hex");
    }

    @Test
    void rejectsInventedEnumsAndUnreviewedOrNonPrimaryEvidence() {
        assertContains(validate(validDataset(6).replaceFirst(
                "TREATY_STATUS", "INVENTED")), "invalid recordType");
        assertContains(validate(validDataset(6).replaceFirst(
                "\"DAY\"", "\"DECADE\"")), "invalid effectiveOnPrecision");
        assertContains(validate(validDataset(6).replaceFirst(
                "\"PRIMARY\"", "\"WIRE_REPRINT\"")),
                "publicationPath must be PRIMARY");
        assertContains(validate(validDataset(6).replaceFirst(
                "HUMAN_REVIEWED", "AUTO")),
                "reviewStatus must be HUMAN_REVIEWED");
    }

    private GovernanceLedgerValidator.ValidatedDataset validate(String json) {
        return validator.validate(json.getBytes(StandardCharsets.UTF_8), CLOCK);
    }

    private static void assertContains(
            GovernanceLedgerValidator.ValidatedDataset dataset,
            String fragment) {
        assertTrue(dataset.errors().stream().anyMatch(error -> error.contains(fragment)),
                () -> "Expected error containing '" + fragment + "' but got "
                        + dataset.errors());
    }

    public static String validDataset(int count) {
        return IntStream.range(0, count)
                .mapToObj(GovernanceLedgerValidatorTest::record)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String record(int index) {
        int source = index % 3;
        String sourceCode = source == 0 ? "UNOOSA" : source == 1 ? "FAA" : "GOVINFO";
        String sourceUrl = source == 0
                ? "https://www.unoosa.org/oosa/en/ourwork/spacelaw/treaties/status/index.html"
                : source == 1
                        ? "https://www.faa.gov/space/licenses"
                        : "https://www.govinfo.gov/app/details/FR-2020-12-10/2020-22042";
        String type = source == 0 ? "TREATY_STATUS"
                : source == 1 ? "LICENSE_FRAMEWORK" : "REGULATORY_NOTICE";
        return """
                {"recordId":"REC-%d","recordType":"%s","jurisdiction":"TEST",
                 "subject":"Reviewed governance subject %d","status":"PUBLISHED",
                 "effectiveOn":"2025-01-%02d","effectiveOnPrecision":"DAY",
                 "sourceCode":"%s","sourceUrl":"%s","accessedOn":"2026-07-15",
                 "contentSha256":"%064x","publicationPath":"PRIMARY",
                 "factSummary":"Reviewer-authored factual summary number %d with enough bounded context.",
                 "reviewStatus":"HUMAN_REVIEWED"}
                """.formatted(index, type, index, index % 27 + 1,
                sourceCode, sourceUrl, index + 1, index);
    }
}
