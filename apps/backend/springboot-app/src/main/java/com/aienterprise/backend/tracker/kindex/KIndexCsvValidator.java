package com.aienterprise.backend.tracker.kindex;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates the reviewed, comma-free annual energy CSV used by WP3.2. */
public final class KIndexCsvValidator {

    public static final String HEADER =
            "year,primary_energy_twh,accounting_basis,source_name,source_url,accessed_on";
    private static final Set<String> ACCOUNTING_BASES =
            Set.of("SUBSTITUTION", "USEFUL");
    private static final int MIN_OBSERVATIONS = 10;
    private static final int MAX_OBSERVATIONS = 200;

    public ValidatedDataset validate(String csv, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        List<String> errors = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return invalid("csv: must not be blank");
        }

        List<String> lines = csv.lines().toList();
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
            return invalid("csv: header must exactly match " + HEADER);
        }

        int rowCount = lines.size() - 1;
        if (rowCount < MIN_OBSERVATIONS || rowCount > MAX_OBSERVATIONS) {
            errors.add("csv: observation count must be between 10 and 200");
        }

        int maximumYear = LocalDate.now(clock).getYear() - 1;
        Set<Integer> years = new HashSet<>();
        List<ParsedRow> parsedRows = new ArrayList<>();
        for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            String path = "row " + (lineIndex + 1);
            if (line.indexOf('"') >= 0) {
                errors.add(path + ": quoted fields are not supported; use semicolons");
            }
            String[] fields = line.split(",", -1);
            if (fields.length != 6) {
                errors.add(path + ": must contain exactly six comma-free fields");
                continue;
            }

            Integer year = parseYear(fields[0], maximumYear, path, errors);
            BigDecimal energy = parseEnergy(fields[1], path, errors);
            String basis = required(fields[2], "accounting_basis", path, 20, errors);
            String sourceName = required(fields[3], "source_name", path, 200, errors);
            String sourceUrl = required(fields[4], "source_url", path, 1000, errors);
            LocalDate accessedOn = parseDate(fields[5], path, errors);

            if (basis != null && !ACCOUNTING_BASES.contains(basis)) {
                errors.add(path + ": accounting_basis must be SUBSTITUTION or USEFUL");
            }
            if (sourceUrl != null && !safeHttps(sourceUrl)) {
                errors.add(path + ": source_url must be a safe HTTPS URL");
            }
            if (accessedOn != null && accessedOn.isAfter(LocalDate.now(clock))) {
                errors.add(path + ": accessed_on must not be in the future");
            }
            if (year != null && !years.add(year)) {
                errors.add(path + ": duplicate year " + year);
            }
            if (year != null && energy != null && basis != null && sourceName != null
                    && sourceUrl != null && accessedOn != null) {
                parsedRows.add(new ParsedRow(
                        new RawObservation(year, energy),
                        new DatasetMetadata(
                                basis, sourceName, sourceUrl, accessedOn)));
            }
        }

        DatasetMetadata metadata = parsedRows.isEmpty()
                ? null : parsedRows.getFirst().metadata();
        if (metadata != null) {
            boolean mixedBasis = parsedRows.stream().anyMatch(row ->
                    !metadata.accountingBasis().equals(
                            row.metadata().accountingBasis()));
            if (mixedBasis) {
                errors.add("csv: all rows must use one consistent accounting_basis");
            }
            boolean mixedSource = parsedRows.stream().anyMatch(row ->
                    !metadata.sourceName().equals(row.metadata().sourceName())
                            || !metadata.sourceUrl().equals(row.metadata().sourceUrl())
                            || !metadata.accessedOn().equals(
                                    row.metadata().accessedOn()));
            if (mixedSource) {
                errors.add("csv: all rows must use consistent source metadata");
            }
        }

        List<RawObservation> observations = parsedRows.stream()
                .map(ParsedRow::observation)
                .sorted(Comparator.comparingInt(RawObservation::year))
                .toList();
        return new ValidatedDataset(observations, metadata, errors);
    }

    private static Integer parseYear(
            String raw, int maximumYear, String path, List<String> errors) {
        try {
            int year = Integer.parseInt(raw);
            if (year < 1800 || year > maximumYear) {
                errors.add(path + ": year must be within 1800.." + maximumYear);
            }
            return year;
        } catch (NumberFormatException malformed) {
            errors.add(path + ": year must be an integer");
            return null;
        }
    }

    private static BigDecimal parseEnergy(
            String raw, String path, List<String> errors) {
        try {
            BigDecimal value = new BigDecimal(raw);
            if (!Double.isFinite(value.doubleValue())) {
                errors.add(path + ": primary_energy_twh must be finite");
            } else if (value.signum() <= 0) {
                errors.add(path + ": primary_energy_twh must be positive");
            }
            return value;
        } catch (NumberFormatException malformed) {
            errors.add(path + ": primary_energy_twh must be numeric and finite");
            return null;
        }
    }

    private static LocalDate parseDate(
            String raw, String path, List<String> errors) {
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException malformed) {
            errors.add(path + ": accessed_on must be an ISO date");
            return null;
        }
    }

    private static String required(
            String raw,
            String field,
            String path,
            int maximumLength,
            List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(path + ": " + field + " must be nonblank");
            return null;
        }
        if (!raw.equals(raw.trim())) {
            errors.add(path + ": " + field + " must not have surrounding whitespace");
        }
        if (raw.length() > maximumLength) {
            errors.add(path + ": " + field + " exceeds " + maximumLength
                    + " characters");
        }
        return raw;
    }

    private static boolean safeHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && !ipLiteral(uri.getHost());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean ipLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
    }

    private static ValidatedDataset invalid(String error) {
        return new ValidatedDataset(List.of(), null, List.of(error));
    }

    public record RawObservation(int year, BigDecimal primaryEnergyTwh) {
    }

    public record DatasetMetadata(
            String accountingBasis,
            String sourceName,
            String sourceUrl,
            LocalDate accessedOn) {
    }

    public record ValidatedDataset(
            List<RawObservation> observations,
            DatasetMetadata metadata,
            List<String> errors) {

        public ValidatedDataset {
            observations = List.copyOf(observations);
            errors = List.copyOf(errors);
        }
    }

    private record ParsedRow(
            RawObservation observation, DatasetMetadata metadata) {
    }
}
