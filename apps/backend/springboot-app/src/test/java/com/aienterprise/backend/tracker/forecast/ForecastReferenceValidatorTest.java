package com.aienterprise.backend.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

public class ForecastReferenceValidatorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
    private final ForecastReferenceValidator validator = new ForecastReferenceValidator();

    @Test
    void acceptsFlexibleReviewedDatasetsWithoutAnExactCountPin() {
        assertEquals(6, validate(validDataset(6)).records().size());
        assertEquals(10, validate(validDataset(10)).records().size());
    }

    @Test
    void emptyInputFailsClosedAsAValidationError() {
        assertContains(validator.validate(new byte[0], CLOCK), "invalid JSON");
        assertContains(validator.validate(null, CLOCK), "invalid JSON");
    }

    @Test
    void rejectsUnknownNestedContentAndScoringKeys() {
        assertContains(validate(validDataset(6).replaceFirst(
                "\\{", "{\"unexpected\":true,")), "unknown key unexpected");
        assertContains(validate(validDataset(6).replaceFirst(
                "\"factSummary\":",
                "\"metadata\":{\"body\":\"copied text\"},\"factSummary\":")),
                "prohibited key body");
        assertContains(validate(validDataset(6).replaceFirst(
                "\"factSummary\":",
                "\"score\":7,\"factSummary\":")), "prohibited key score");
    }

    @Test
    void rejectsDuplicateKeysUnsafeHostsAndChangedCanonicalHashes() {
        assertContains(validate(validDataset(6).replace("TEST-1", "TEST-0")),
                "duplicate forecastKey TEST-0");
        assertContains(validate(validDataset(6).replaceFirst(
                "https://www.nasa.gov", "https://attacker.example")),
                "official HTTPS host");
        assertContains(validate(validDataset(6).replaceFirst(
                "A bounded target definition", "A changed target definition")),
                "contentSha256 does not match canonical record");
    }

    @Test
    void rejectsInventedEnumsAndFutureDates() {
        assertContains(validate(validDataset(6).replaceFirst(
                "LANDING", "UNKNOWN_TRACK")), "invalid trackCode");
        assertContains(validate(validDataset(6).replaceFirst(
                "DIRECT", "INVENTED")), "invalid relationKind");
        assertContains(validate(validDataset(6).replaceFirst(
                "2026-07-15", "2026-07-16")), "accessedOn must not be in the future");
    }

    @Test
    void rejectsCrowdValuesAndInventedCurrentInstitutionalYears() {
        String crowdValue = record(
                "CROWD-VALUE", "CROWD", "METACULUS", "LANDING",
                "AWAITING_AUTHORIZATION", "DIRECT", "2045.0", "", "");
        String currentValue = record(
                "CURRENT-VALUE", "INSTITUTIONAL", "NASA", "LANDING",
                "CURRENT", "DIRECT", "2040.0", "", "");
        String validTail = IntStream.range(0, 5)
                .mapToObj(ForecastReferenceValidatorTest::defaultRecord)
                .collect(java.util.stream.Collectors.joining(","));

        assertContains(validate("[" + crowdValue + "," + validTail + "]"),
                "crowd reference must not carry a forecast value");
        assertContains(validate("[" + currentValue + "," + validTail + "]"),
                "current institutional year requires PRECURSOR or LEGACY status");
    }

    @Test
    void rejectsYearsMorePreciseThanTheOneDecimalLedger() {
        String overPrecise = record(
                "PRECISE", "INSTITUTIONAL", "NASA", "LANDING",
                "PRECURSOR", "PRECURSOR", "2040.25", "", "");
        String validTail = IntStream.range(0, 5)
                .mapToObj(ForecastReferenceValidatorTest::defaultRecord)
                .collect(java.util.stream.Collectors.joining(","));

        assertContains(validate("[" + overPrecise + "," + validTail + "]"),
                "at most one decimal place");
    }

    @Test
    void rejectsZeroAsAnActualForecastYearInsteadOfTreatingItAsMissing() {
        String zeroYear = record(
                "ZERO-YEAR", "INSTITUTIONAL", "NASA", "LANDING",
                "PRECURSOR", "PRECURSOR", "0.0", "", "");
        String validTail = IntStream.range(0, 5)
                .mapToObj(ForecastReferenceValidatorTest::defaultRecord)
                .collect(java.util.stream.Collectors.joining(","));

        assertContains(validate("[" + zeroYear + "," + validTail + "]"),
                "forecast years must be between 2026.0 and 2300.0");
    }

    private ForecastReferenceValidator.ValidatedDataset validate(String json) {
        return validator.validate(json.getBytes(StandardCharsets.UTF_8), CLOCK);
    }

    private static void assertContains(
            ForecastReferenceValidator.ValidatedDataset dataset,
            String fragment) {
        assertTrue(dataset.errors().stream().anyMatch(error -> error.contains(fragment)),
                () -> "Expected error containing '" + fragment + "' but got "
                        + dataset.errors());
    }

    public static String validDataset(int count) {
        return validDataset(count, "");
    }

    public static String validDataset(int count, String questionSuffix) {
        return IntStream.range(0, count)
                .mapToObj(index -> defaultRecord(index, questionSuffix))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String defaultRecord(int index) {
        return defaultRecord(index, "");
    }

    private static String defaultRecord(int index, String questionSuffix) {
        String track = switch (index % 3) {
            case 0 -> "LANDING";
            case 1 -> "RETURN";
            default -> "SETTLEMENT";
        };
        return record("TEST-" + index, "INSTITUTIONAL", "NASA", track,
                "UNDATED", "DIRECT", "", "", "", questionSuffix);
    }

    private static String record(
            String key,
            String sourceType,
            String sourceName,
            String track,
            String displayStatus,
            String relation,
            String year,
            String low,
            String high) {
        return record(key, sourceType, sourceName, track, displayStatus, relation,
                year, low, high, "");
    }

    private static String record(
            String key,
            String sourceType,
            String sourceName,
            String track,
            String displayStatus,
            String relation,
            String year,
            String low,
            String high,
            String questionSuffix) {
        String question = "Reviewed question " + key + questionSuffix;
        String definition = "A bounded target definition for " + key + ".";
        String url = switch (sourceName) {
            case "NASA" -> "https://www.nasa.gov/moontomarsarchitecture-strategyandobjectives/";
            case "SPACEX" -> "https://www.spacex.com/mars";
            default -> "https://www.metaculus.com/questions/3515/";
        };
        String locator = sourceName.equals("METACULUS") ? "post:3515" : "current page";
        String summary = "Reviewer-authored factual summary for " + key
                + " with enough context to explain the comparison boundary.";
        String canonical = String.join("|", key, sourceType, sourceName, track,
                question, definition, displayStatus, year, low, high, relation,
                url, locator, "2026-07-15", "REVIEWED_REFERENCE", summary);
        String hash = sha256(canonical);
        return """
                {"forecastKey":"%s","sourceType":"%s","sourceName":"%s",
                 "trackCode":"%s","question":"%s","targetDefinition":"%s",
                 "displayStatus":"%s",%s%s%s"relationKind":"%s",
                 "sourceUrl":"%s","sourceLocator":"%s","accessedOn":"2026-07-15",
                 "ingestionMode":"REVIEWED_REFERENCE","contentSha256":"%s",
                 "factSummary":"%s"}
                """.formatted(key, sourceType, sourceName, track, question, definition,
                displayStatus,
                numberField("forecastYear", year),
                numberField("forecastYearLow", low),
                numberField("forecastYearHigh", high),
                relation, url, locator, hash, summary).replaceAll("\\s+", " ");
    }

    private static String numberField(String name, String value) {
        return value.isBlank() ? "" : "\"" + name + "\":" + value + ",";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
