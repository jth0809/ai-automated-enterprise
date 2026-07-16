package com.aienterprise.backend.tracker.layerb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class HumanPresenceCsvValidatorTest {

    private static final String VALID = """
            # dataset_version=human-presence-v9
            # source_label=Reviewed orbital population history
            # source_url=https://planet4589.org/space/astro/web/pop.html
            # accessed_on=2026-07-16
            # complete_through_utc=2025-01-01T00:00:00Z
            timestamp_utc,orbit_population
            2024-01-01T00:00:00Z,2
            2024-07-01T00:00:00Z,4
            2025-01-01T00:00:00Z,3
            2025-02-01T00:00:00Z,5
            """;

    private final HumanPresenceCsvValidator validator = new HumanPresenceCsvValidator();

    @Test
    void acceptsCanonicalDatasetAndRetainsDraftRowsAfterCompletionBoundary() {
        var result = validator.validate(VALID.getBytes(UTF_8));

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertNotNull(result.dataset());
        assertEquals("human-presence-v9", result.dataset().datasetVersion());
        assertEquals("Reviewed orbital population history", result.dataset().sourceLabel());
        assertEquals("https://planet4589.org/space/astro/web/pop.html",
                result.dataset().sourceUrl());
        assertEquals(LocalDate.of(2026, 7, 16), result.dataset().accessedOn());
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"),
                result.dataset().completeThroughUtc());
        assertEquals(4, result.dataset().transitions().size());
        assertEquals(5, result.dataset().transitions().getLast().orbitPopulation());
    }

    @Test
    void rejectsUnknownAndMissingMetadata() {
        String csv = VALID
                .replace("# source_label=Reviewed orbital population history\n", "")
                .replace("# accessed_on=2026-07-16",
                        "# accessed_on=2026-07-16\n# unknown=value");

        var result = validator.validate(csv.getBytes(UTF_8));

        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("source_label")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("unknown")));
        assertEquals(null, result.dataset());
    }

    @Test
    void rejectsUnsafeSourceUrl() {
        String csv = VALID.replace(
                "https://planet4589.org/space/astro/web/pop.html",
                "https://user@evil.test:8443/pop.html#fragment");

        var result = validator.validate(csv.getBytes(UTF_8));

        assertTrue(result.errors().stream().anyMatch(error -> error.contains("source_url")));
    }

    @Test
    void rejectsMalformedHeaderAndExtraColumns() {
        String csv = VALID.replace(
                "timestamp_utc,orbit_population",
                "timestamp,orbit_population,mission");

        var result = validator.validate(csv.getBytes(UTF_8));

        assertTrue(result.errors().stream().anyMatch(error -> error.contains("header")));
    }

    @Test
    void rejectsDuplicateAndDescendingTimestamps() {
        String duplicate = VALID.replace(
                "2024-07-01T00:00:00Z,4",
                "2024-01-01T00:00:00Z,4");
        String descending = VALID.replace(
                "2024-07-01T00:00:00Z,4",
                "2023-12-31T23:59:59Z,4");

        var duplicateResult = validator.validate(duplicate.getBytes(UTF_8));
        var descendingResult = validator.validate(descending.getBytes(UTF_8));

        assertTrue(duplicateResult.errors().stream()
                .anyMatch(error -> error.contains("strictly increasing")));
        assertTrue(descendingResult.errors().stream()
                .anyMatch(error -> error.contains("strictly increasing")));
    }

    @Test
    void rejectsPopulationOutsideBoundedIntegerRange() {
        String negative = VALID.replace("2024-07-01T00:00:00Z,4",
                "2024-07-01T00:00:00Z,-1");
        String tooLarge = VALID.replace("2024-07-01T00:00:00Z,4",
                "2024-07-01T00:00:00Z,51");
        String decimal = VALID.replace("2024-07-01T00:00:00Z,4",
                "2024-07-01T00:00:00Z,4.5");

        assertTrue(validator.validate(negative.getBytes(UTF_8)).errors().stream()
                .anyMatch(error -> error.contains("orbit_population")));
        assertTrue(validator.validate(tooLarge.getBytes(UTF_8)).errors().stream()
                .anyMatch(error -> error.contains("orbit_population")));
        assertTrue(validator.validate(decimal.getBytes(UTF_8)).errors().stream()
                .anyMatch(error -> error.contains("orbit_population")));
    }

    @Test
    void rejectsMissingAnnualBoundaryAndMissingCompletionRow() {
        String missingStart = VALID.replace("2024-01-01T00:00:00Z,2\n", "");
        String missingCutoff = VALID.replace("2025-01-01T00:00:00Z,3\n", "");

        var missingStartResult = validator.validate(missingStart.getBytes(UTF_8));
        var missingCutoffResult = validator.validate(missingCutoff.getBytes(UTF_8));

        assertTrue(missingStartResult.errors().stream()
                .anyMatch(error -> error.contains("annual boundary")));
        assertTrue(missingCutoffResult.errors().stream()
                .anyMatch(error -> error.contains("complete_through_utc")));
    }

    @Test
    void rejectsOversizedInputBeforeParsing() {
        byte[] oversized = new byte[256 * 1024 + 1];

        var result = validator.validate(oversized);

        assertTrue(result.errors().stream().anyMatch(error -> error.contains("256 KiB")));
    }
}
