package com.aienterprise.backend.tracker.layerb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict parser for the small, reviewed orbital-population transition CSV.
 * It accepts numeric facts and provenance metadata only; no names, mission
 * descriptions, source body, or quoted text are part of the contract.
 */
public final class HumanPresenceCsvValidator {

    public record ValidatedDataset(HumanPresenceDataset dataset, List<String> errors) {
        public ValidatedDataset {
            errors = List.copyOf(errors);
        }
    }

    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_TRANSITIONS = 5000;
    private static final String HEADER = "timestamp_utc,orbit_population";
    private static final Pattern VERSION = Pattern.compile("human-presence-v[1-9][0-9]*");
    private static final Set<String> REQUIRED_METADATA = Set.of(
            "dataset_version", "source_label", "source_url", "accessed_on",
            "complete_through_utc");

    public ValidatedDataset validate(byte[] bytes) {
        List<String> errors = new ArrayList<>();
        if (bytes == null) {
            return invalid("human-presence: input is required");
        }
        if (bytes.length > MAX_BYTES) {
            return invalid("human-presence: input exceeds 256 KiB");
        }

        String text;
        try {
            text = UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformedUtf8) {
            return invalid("human-presence: input must be valid UTF-8");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        String[] lines = text.split("\\R", -1);
        Map<String, String> metadata = new HashMap<>();
        int lineIndex = 0;
        while (lineIndex < lines.length && lines[lineIndex].startsWith("# ")) {
            parseMetadata(lines[lineIndex], lineIndex + 1, metadata, errors);
            lineIndex++;
        }
        for (String required : REQUIRED_METADATA) {
            if (!metadata.containsKey(required) || metadata.get(required).isBlank()) {
                errors.add("human-presence: missing metadata " + required);
            }
        }

        if (lineIndex >= lines.length || !HEADER.equals(lines[lineIndex])) {
            errors.add("human-presence: header must be exactly " + HEADER);
        } else {
            lineIndex++;
        }

        List<HumanPresenceTransition> transitions = new ArrayList<>();
        Instant previous = null;
        for (; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (line.isEmpty() && isOnlyTrailingEmpty(lines, lineIndex)) {
                break;
            }
            int displayLine = lineIndex + 1;
            if (line.isEmpty()) {
                errors.add("human-presence line " + displayLine + ": blank rows are not allowed");
                continue;
            }
            String[] fields = line.split(",", -1);
            if (fields.length != 2 || fields[0].contains("\"") || fields[1].contains("\"")) {
                errors.add("human-presence line " + displayLine
                        + ": expected two unquoted columns");
                continue;
            }

            Instant timestamp = parseUtcInstant(fields[0], "timestamp_utc", displayLine, errors);
            Integer population = parsePopulation(fields[1], displayLine, errors);
            if (timestamp == null || population == null) {
                continue;
            }
            if (previous != null && !timestamp.isAfter(previous)) {
                errors.add("human-presence line " + displayLine
                        + ": timestamps must be strictly increasing");
            }
            previous = timestamp;
            transitions.add(new HumanPresenceTransition(timestamp, population));
            if (transitions.size() > MAX_TRANSITIONS) {
                errors.add("human-presence: more than 5000 transitions");
                break;
            }
        }

        String datasetVersion = metadata.getOrDefault("dataset_version", "");
        if (!VERSION.matcher(datasetVersion).matches()) {
            errors.add("human-presence: invalid dataset_version");
        }
        String sourceLabel = metadata.getOrDefault("source_label", "");
        if (sourceLabel.length() > 200) {
            errors.add("human-presence: source_label exceeds 200 characters");
        }
        String sourceUrl = metadata.getOrDefault("source_url", "");
        if (!safeSourceUrl(sourceUrl)) {
            errors.add("human-presence: unsafe source_url");
        }
        LocalDate accessedOn = parseDate(metadata.get("accessed_on"), errors);
        Instant completeThrough = parseUtcInstant(
                metadata.get("complete_through_utc"), "complete_through_utc", 0, errors);
        validateCoverage(transitions, completeThrough, errors);

        if (!errors.isEmpty()) {
            return new ValidatedDataset(null, errors);
        }
        return new ValidatedDataset(new HumanPresenceDataset(
                datasetVersion, sourceLabel, sourceUrl, accessedOn,
                completeThrough, transitions), List.of());
    }

    private static void parseMetadata(
            String line, int lineNumber, Map<String, String> metadata, List<String> errors) {
        String declaration = line.substring(2);
        int equals = declaration.indexOf('=');
        if (equals <= 0) {
            errors.add("human-presence line " + lineNumber + ": malformed metadata");
            return;
        }
        String key = declaration.substring(0, equals);
        String value = declaration.substring(equals + 1);
        if (!REQUIRED_METADATA.contains(key)) {
            errors.add("human-presence line " + lineNumber + ": unknown metadata " + key);
            return;
        }
        if (metadata.putIfAbsent(key, value) != null) {
            errors.add("human-presence line " + lineNumber + ": duplicate metadata " + key);
        }
    }

    private static Integer parsePopulation(String raw, int lineNumber, List<String> errors) {
        try {
            int population = Integer.parseInt(raw);
            if (population < 0 || population > 50) {
                throw new NumberFormatException("out of range");
            }
            return population;
        } catch (NumberFormatException invalid) {
            errors.add("human-presence line " + lineNumber
                    + ": orbit_population must be an integer from 0 to 50");
            return null;
        }
    }

    private static Instant parseUtcInstant(
            String raw, String field, int lineNumber, List<String> errors) {
        String at = lineNumber > 0 ? " line " + lineNumber : "";
        if (raw == null || !raw.endsWith("Z")) {
            errors.add("human-presence" + at + ": " + field + " must be a UTC Z instant");
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException invalid) {
            errors.add("human-presence" + at + ": invalid " + field);
            return null;
        }
    }

    private static LocalDate parseDate(String raw, List<String> errors) {
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException invalid) {
            errors.add("human-presence: invalid accessed_on");
            return null;
        }
    }

    private static boolean safeSourceUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "planet4589.org".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getFragment() == null
                    && uri.getPath() != null
                    && !uri.getPath().isBlank();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static void validateCoverage(
            List<HumanPresenceTransition> transitions,
            Instant completeThrough,
            List<String> errors) {
        if (transitions.isEmpty() || completeThrough == null) {
            errors.add("human-presence: at least one complete year is required");
            return;
        }
        Instant first = transitions.getFirst().timestampUtc();
        if (!isJanuaryBoundary(first)) {
            errors.add("human-presence: first transition must be an annual boundary");
        }
        if (!isJanuaryBoundary(completeThrough)) {
            errors.add("human-presence: complete_through_utc must be a January 1 UTC boundary");
            return;
        }

        Set<Instant> timestamps = new HashSet<>();
        transitions.forEach(transition -> timestamps.add(transition.timestampUtc()));
        if (!timestamps.contains(completeThrough)) {
            errors.add("human-presence: complete_through_utc must have a matching transition");
        }
        if (!completeThrough.isAfter(first)) {
            errors.add("human-presence: at least one complete year is required");
            return;
        }

        int firstYear = first.atZone(ZoneOffset.UTC).getYear();
        int cutoffYear = completeThrough.atZone(ZoneOffset.UTC).getYear();
        for (int year = firstYear; year <= cutoffYear; year++) {
            Instant boundary = LocalDate.of(year, 1, 1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!timestamps.contains(boundary)) {
                errors.add("human-presence: missing annual boundary " + year + "-01-01");
            }
        }
    }

    private static boolean isJanuaryBoundary(Instant instant) {
        var utc = instant.atZone(ZoneOffset.UTC);
        return utc.getMonthValue() == 1 && utc.getDayOfMonth() == 1
                && utc.toLocalTime().equals(java.time.LocalTime.MIDNIGHT);
    }

    private static boolean isOnlyTrailingEmpty(String[] lines, int from) {
        for (int index = from; index < lines.length; index++) {
            if (!lines[index].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static ValidatedDataset invalid(String error) {
        return new ValidatedDataset(null, List.of(error));
    }
}
