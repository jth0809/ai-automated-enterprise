package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GoldenOutputTest {

    @Test
    void jsonFieldOrderDoesNotAffectCanonicalAgreement() {
        GoldenOutput first = GoldenOutput.fromExpectedJson("""
                {"relevant":true,"nodeCode":"P1-REUSE-LV","eventType":"FLIGHT_TEST",
                 "claimedLevel":5,"actor":"Aster Works","occurredOn":"2026-01-12",
                 "publicationPath":"PRIMARY"}
                """);
        GoldenOutput reordered = GoldenOutput.fromExpectedJson("""
                {"publicationPath":"PRIMARY","occurredOn":"2026-01-12",
                 "actor":"Aster Works","claimedLevel":5,"eventType":"FLIGHT_TEST",
                 "nodeCode":"P1-REUSE-LV","relevant":true}
                """);

        assertEquals(Set.of(), first.diff(reordered));
        assertEquals(first.canonicalSemanticJson(), reordered.canonicalSemanticJson());
    }

    @Test
    void diffReportsEverySemanticFieldIndependently() {
        GoldenOutput expected = output("P1-REUSE-LV", "FLIGHT_TEST", 5,
                "Aster Works", LocalDate.of(2026, 1, 12), "PRIMARY", null);
        GoldenOutput actual = new GoldenOutput(
                false, null, null, null, null, null, null, null);

        assertEquals(Set.of(
                "relevant", "nodeCode", "eventType", "claimedLevel", "actor",
                "occurredOn", "publicationPath"), expected.diff(actual));
    }

    @Test
    void validationDistinguishesSchemaAndQuoteFailures() {
        GoldenInput input = new GoldenInput(
                1, "GOLD-TEST", "SYNTHETIC", "Title",
                "The authored stage was recovered intact.", "golden-output-v1");
        GoldenOutput quoteMismatch = output(
                "P1-REUSE-LV", "FLIGHT_TEST", 5, "Aster Works",
                LocalDate.of(2026, 1, 12), "PRIMARY", "A sentence not in the fixture.");
        GoldenOutput schemaInvalid = output(
                "P1-REUSE-LV", "FLIGHT_TEST", 5, null,
                LocalDate.of(2026, 1, 12), "PRIMARY", "recovered intact");

        assertEquals("QUOTE_MISMATCH", quoteMismatch.validationError(input));
        assertEquals("SCHEMA_INVALID", schemaInvalid.validationError(input));
    }

    static GoldenOutput output(
            String nodeCode,
            String eventType,
            Integer claimedLevel,
            String actor,
            LocalDate occurredOn,
            String publicationPath,
            String evidenceQuote) {
        return new GoldenOutput(
                true, nodeCode, eventType, claimedLevel, actor,
                occurredOn, publicationPath, evidenceQuote);
    }
}
