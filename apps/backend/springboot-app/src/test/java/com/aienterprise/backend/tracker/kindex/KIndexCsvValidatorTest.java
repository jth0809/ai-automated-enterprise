package com.aienterprise.backend.tracker.kindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class KIndexCsvValidatorTest {

    private static final String HEADER =
            "year,primary_energy_twh,accounting_basis,source_name,source_url,accessed_on";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
    private static final KIndexCsvValidator VALIDATOR = new KIndexCsvValidator();

    @Test
    void acceptsTenRowsAndSortsUnsortedYears() {
        List<Integer> years = IntStream.rangeClosed(2010, 2019).boxed()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.reverse(years);

        var result = VALIDATOR.validate(csv(years), CLOCK);

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals(10, result.observations().size());
        assertEquals(2010, result.observations().getFirst().year());
        assertEquals(2019, result.observations().getLast().year());
        assertEquals("SUBSTITUTION", result.metadata().accountingBasis());
    }

    @Test
    void acceptsTwelveRowsWithYearGaps() {
        List<Integer> years = List.of(
                1990, 1991, 1992, 1994, 1995, 1998,
                2001, 2005, 2010, 2015, 2020, 2024);

        var result = VALIDATOR.validate(csv(years), CLOCK);

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals(years, result.observations().stream()
                .map(KIndexCsvValidator.RawObservation::year).toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDatasets")
    void rejectsInvalidCsvContracts(InvalidDataset invalid) {
        var result = VALIDATOR.validate(invalid.csv(), CLOCK);

        assertFalse(result.errors().isEmpty(), invalid.name());
        assertTrue(result.errors().stream().anyMatch(
                error -> error.contains(invalid.expectedError())),
                () -> invalid.name() + ": " + String.join("\n", result.errors()));
    }

    private static Stream<InvalidDataset> invalidDatasets() {
        String valid = csv(IntStream.rangeClosed(2010, 2019).boxed().toList());
        String duplicate = csv(List.of(
                2010, 2011, 2012, 2013, 2014,
                2015, 2016, 2017, 2018, 2018));
        String tooFew = csv(IntStream.rangeClosed(2010, 2018).boxed().toList());
        String tooMany = csv(IntStream.rangeClosed(1825, 2025).boxed().toList());
        String mixedBasis = replaceLine(valid, 5,
                line -> line.replace("SUBSTITUTION", "USEFUL"));
        String mixedSource = replaceLine(valid, 5,
                line -> line.replace("Reviewed energy source", "Different source"));
        String nonpositive = replaceLine(valid, 6,
                line -> line.replace(",1005.0,", ",0,"));
        String nonfinite = replaceLine(valid, 6,
                line -> line.replace(",1005.0,", ",1e10000,"));
        String unsafeUrl = valid.replace(
                "https://example.test/energy", "https://user@example.test/energy");
        String nonstandardPort = valid.replace(
                "https://example.test/energy", "https://example.test:8443/energy");
        String futureAccess = valid.replace("2026-07-15", "2026-07-16");
        String currentYear = replaceLine(valid, 6,
                line -> line.replaceFirst("2015,", "2026,"));
        String futureYear = replaceLine(valid, 6,
                line -> line.replaceFirst("2015,", "2027,"));
        String malformedHeader = valid.replace(HEADER,
                "year,energy,accounting_basis,source_name,source_url,accessed_on");
        String quotedField = replaceLine(valid, 5,
                line -> line.replace("Reviewed energy source", "\"Energy, source\""));

        return Stream.of(
                invalid("duplicate year", duplicate, "duplicate year"),
                invalid("fewer than ten", tooFew, "between 10 and 200"),
                invalid("more than two hundred", tooMany, "between 10 and 200"),
                invalid("mixed basis", mixedBasis, "consistent accounting_basis"),
                invalid("mixed source", mixedSource, "consistent source metadata"),
                invalid("nonpositive energy", nonpositive, "must be positive"),
                invalid("nonfinite energy", nonfinite, "must be finite"),
                invalid("URL userinfo", unsafeUrl, "safe HTTPS URL"),
                invalid("URL nonstandard port", nonstandardPort, "safe HTTPS URL"),
                invalid("future access date", futureAccess, "must not be in the future"),
                invalid("current observation year", currentYear, "1800..2025"),
                invalid("future observation year", futureYear, "1800..2025"),
                invalid("malformed header", malformedHeader, "header must exactly match"),
                invalid("quoted comma field", quotedField,
                        "quoted fields are not supported"));
    }

    private static InvalidDataset invalid(
            String name, String csv, String expectedError) {
        return new InvalidDataset(name, csv, expectedError);
    }

    private static String csv(List<Integer> years) {
        StringBuilder result = new StringBuilder(HEADER).append('\n');
        for (int index = 0; index < years.size(); index++) {
            result.append(years.get(index)).append(',')
                    .append("100").append(index).append(".0")
                    .append(",SUBSTITUTION,Reviewed energy source,")
                    .append("https://example.test/energy,2026-07-15\n");
        }
        return result.toString();
    }

    private static String replaceLine(
            String csv, int lineIndex, UnaryOperator<String> replacement) {
        List<String> lines = new ArrayList<>(csv.lines().toList());
        lines.set(lineIndex, replacement.apply(lines.get(lineIndex)));
        return String.join("\n", lines) + "\n";
    }

    private record InvalidDataset(String name, String csv, String expectedError) {
        @Override
        public String toString() {
            return name;
        }
    }
}
