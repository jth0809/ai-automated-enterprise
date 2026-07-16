package com.aienterprise.backend.tracker.forecast;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict validator for value-free crowd metadata and reviewed institution facts. */
public final class ForecastReferenceValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MIN_RECORDS = 6;
    private static final int MAX_RECORDS = 50;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "forecastKey", "sourceType", "sourceName", "trackCode", "question",
            "targetDefinition", "displayStatus", "forecastYear", "forecastYearLow",
            "forecastYearHigh", "relationKind", "sourceUrl", "sourceLocator",
            "accessedOn", "ingestionMode", "contentSha256", "factSummary");
    private static final Set<String> SOURCE_TYPES = Set.of("CROWD", "INSTITUTIONAL");
    private static final Set<String> TRACKS = Set.of("LANDING", "RETURN", "SETTLEMENT");
    private static final Set<String> DISPLAY_STATUSES = Set.of(
            "CURRENT", "UNDATED", "PRECURSOR", "LEGACY", "AWAITING_AUTHORIZATION");
    private static final Set<String> RELATIONS = Set.of(
            "DIRECT", "PROXY", "SUPPORTING", "PRECURSOR", "REQUIREMENT");
    private static final Set<String> INGESTION_MODES = Set.of("REVIEWED_REFERENCE", "API");
    private static final Map<String, String> SOURCE_HOSTS = Map.of(
            "NASA", "www.nasa.gov",
            "SPACEX", "www.spacex.com",
            "METACULUS", "www.metaculus.com");
    private static final Set<String> PROHIBITED_KEYS = Set.of(
            "quote", "evidencequote", "body", "html", "pdf", "image", "binary",
            "node", "nodecode", "claimedlevel", "score", "readiness", "eta",
            "etayear", "rawresponse", "token");

    public ValidatedDataset validate(byte[] bytes, Clock clock) {
        List<String> errors = new ArrayList<>();
        List<ForecastReference> records = new ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            return new ValidatedDataset(records, List.of("root: invalid JSON"));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (Exception malformed) {
            return new ValidatedDataset(List.of(), List.of("root: invalid JSON"));
        }
        if (root == null) {
            return new ValidatedDataset(records, List.of("root: invalid JSON"));
        }
        scanProhibited(root, "root", errors);
        if (!root.isArray()) {
            errors.add("root: must be an array");
            return new ValidatedDataset(records, errors);
        }
        if (root.size() < MIN_RECORDS || root.size() > MAX_RECORDS) {
            errors.add("root: record count must be between 6 and 50");
        }

        LocalDate today = LocalDate.now(clock);
        Set<String> seenKeys = new HashSet<>();
        for (int index = 0; index < root.size(); index++) {
            String path = "root[" + index + "]";
            JsonNode node = root.get(index);
            int errorsBefore = errors.size();
            if (!node.isObject()) {
                errors.add(path + ": must be an object");
                continue;
            }
            node.fieldNames().forEachRemaining(field -> {
                if (!ALLOWED_KEYS.contains(field)) {
                    errors.add(path + ": unknown key " + field);
                }
            });

            String key = text(node, "forecastKey", path, 100, errors);
            String sourceType = text(node, "sourceType", path, 15, errors);
            String sourceName = text(node, "sourceName", path, 100, errors);
            String track = text(node, "trackCode", path, 20, errors);
            String question = text(node, "question", path, 500, errors);
            String definition = text(node, "targetDefinition", path, 1_000, errors);
            String display = text(node, "displayStatus", path, 30, errors);
            BigDecimal year = decimal(node, "forecastYear", path, errors);
            BigDecimal low = decimal(node, "forecastYearLow", path, errors);
            BigDecimal high = decimal(node, "forecastYearHigh", path, errors);
            String relation = text(node, "relationKind", path, 20, errors);
            String sourceUrl = text(node, "sourceUrl", path, 1_000, errors);
            String locator = optionalText(node, "sourceLocator", path, 300, errors);
            LocalDate accessedOn = date(node, "accessedOn", path, errors);
            String ingestionMode = text(node, "ingestionMode", path, 20, errors);
            String sha = text(node, "contentSha256", path, 64, errors);
            String summary = text(node, "factSummary", path, 1_000, errors);

            if (key != null && !seenKeys.add(key)) {
                errors.add(path + ": duplicate forecastKey " + key);
            }
            requireEnum(sourceType, SOURCE_TYPES, "sourceType", path, errors);
            requireEnum(track, TRACKS, "trackCode", path, errors);
            requireEnum(display, DISPLAY_STATUSES, "displayStatus", path, errors);
            requireEnum(relation, RELATIONS, "relationKind", path, errors);
            requireEnum(ingestionMode, INGESTION_MODES, "ingestionMode", path, errors);

            String expectedHost = SOURCE_HOSTS.get(sourceName);
            if (sourceUrl != null && !safeOfficialUrl(sourceUrl, expectedHost)) {
                errors.add(path + ": sourceUrl must use the exact official HTTPS host");
            }
            if (sourceName != null && !SOURCE_HOSTS.containsKey(sourceName)) {
                errors.add(path + ": invalid sourceName");
            }
            if ("METACULUS".equals(sourceName) && !"CROWD".equals(sourceType)) {
                errors.add(path + ": METACULUS must be CROWD");
            }
            if (("NASA".equals(sourceName) || "SPACEX".equals(sourceName))
                    && !"INSTITUTIONAL".equals(sourceType)) {
                errors.add(path + ": NASA and SPACEX must be INSTITUTIONAL");
            }
            if (accessedOn != null && accessedOn.isAfter(today)) {
                errors.add(path + ": accessedOn must not be in the future");
            }
            validateYears(year, low, high, path, errors);
            boolean hasValue = year != null || low != null || high != null;
            if ("CROWD".equals(sourceType) && hasValue) {
                errors.add(path + ": crowd reference must not carry a forecast value");
            }
            if ("CROWD".equals(sourceType)
                    && display != null
                    && !"AWAITING_AUTHORIZATION".equals(display)) {
                errors.add(path + ": reviewed crowd metadata must await authorization");
            }
            if ("INSTITUTIONAL".equals(sourceType) && hasValue
                    && display != null
                    && !Set.of("PRECURSOR", "LEGACY").contains(display)) {
                errors.add(path
                        + ": current institutional year requires PRECURSOR or LEGACY status");
            }
            if (Set.of("PRECURSOR", "LEGACY").contains(display) && !hasValue) {
                errors.add(path + ": PRECURSOR and LEGACY records require a year or range");
            }
            if (Set.of("UNDATED", "AWAITING_AUTHORIZATION").contains(display) && hasValue) {
                errors.add(path + ": undated or authorization-blocked records cannot carry years");
            }
            if (summary != null && summary.length() < 40) {
                errors.add(path + ": factSummary must be at least 40 characters");
            }
            if (sha != null && !SHA256.matcher(sha).matches()) {
                errors.add(path + ": contentSha256 must be 64 lowercase hex characters");
            }
            if (sha != null && key != null && sourceType != null && sourceName != null
                    && track != null && question != null && definition != null
                    && display != null && relation != null && sourceUrl != null
                    && accessedOn != null && ingestionMode != null && summary != null) {
                String expected = canonicalHash(key, sourceType, sourceName, track,
                        question, definition, display, year, low, high, relation,
                        sourceUrl, locator, accessedOn, ingestionMode, summary);
                if (!sha.equals(expected)) {
                    errors.add(path + ": contentSha256 does not match canonical record");
                }
            }

            if (errors.size() == errorsBefore) {
                records.add(new ForecastReference(
                        key, sourceType, sourceName, track, question, definition,
                        display, year, low, high, relation, sourceUrl, locator,
                        accessedOn, ingestionMode, sha, summary));
            }
        }
        return new ValidatedDataset(records, errors);
    }

    public static String canonicalHash(ForecastReference record) {
        return canonicalHash(record.forecastKey(), record.sourceType(), record.sourceName(),
                record.trackCode(), record.question(), record.targetDefinition(),
                record.displayStatus(), record.forecastYear(), record.forecastYearLow(),
                record.forecastYearHigh(), record.relationKind(), record.sourceUrl(),
                record.sourceLocator(), record.accessedOn(), record.ingestionMode(),
                record.factSummary());
    }

    private static String canonicalHash(
            String key,
            String sourceType,
            String sourceName,
            String track,
            String question,
            String definition,
            String display,
            BigDecimal year,
            BigDecimal low,
            BigDecimal high,
            String relation,
            String sourceUrl,
            String locator,
            LocalDate accessedOn,
            String ingestionMode,
            String summary) {
        String canonical = String.join("|", key, sourceType, sourceName, track,
                question, definition, display, decimalText(year), decimalText(low),
                decimalText(high), relation, sourceUrl, locator == null ? "" : locator,
                accessedOn.toString(), ingestionMode, summary);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String text(
            JsonNode node, String field, String path, int max, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            errors.add(path + ": " + field + " must be nonblank text");
            return null;
        }
        String normalized = value.asText().trim().replaceAll("\\s+", " ");
        if (normalized.length() > max) {
            errors.add(path + ": " + field + " exceeds " + max + " characters");
        }
        return normalized;
    }

    private static String optionalText(
            JsonNode node, String field, String path, int max, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add(path + ": " + field + " must be nonblank text when present");
            return null;
        }
        String normalized = value.asText().trim().replaceAll("\\s+", " ");
        if (normalized.length() > max) {
            errors.add(path + ": " + field + " exceeds " + max + " characters");
        }
        return normalized;
    }

    private static BigDecimal decimal(
            JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            errors.add(path + ": " + field + " must be numeric when present");
            return null;
        }
        try {
            return value.decimalValue().stripTrailingZeros();
        } catch (RuntimeException malformed) {
            errors.add(path + ": " + field + " is invalid");
            return null;
        }
    }

    private static LocalDate date(
            JsonNode node, String field, String path, List<String> errors) {
        String value = text(node, field, path, 10, errors);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException malformed) {
            errors.add(path + ": " + field + " must be an ISO date");
            return null;
        }
    }

    private static void validateYears(
            BigDecimal year,
            BigDecimal low,
            BigDecimal high,
            String path,
            List<String> errors) {
        for (BigDecimal value : new BigDecimal[] { year, low, high }) {
            if (value == null) {
                continue;
            }
            if (value.stripTrailingZeros().scale() > 1) {
                errors.add(path + ": forecast years support at most one decimal place");
                break;
            }
            if (value.compareTo(new BigDecimal("2026.0")) < 0
                    || value.compareTo(new BigDecimal("2300.0")) > 0) {
                errors.add(path + ": forecast years must be between 2026.0 and 2300.0");
                break;
            }
        }
        if ((low == null) != (high == null)) {
            errors.add(path + ": forecast range requires both low and high");
        }
        if (low != null && high != null && low.compareTo(high) > 0) {
            errors.add(path + ": forecastYearLow must not exceed forecastYearHigh");
        }
        if (year != null && (low != null || high != null)) {
            errors.add(path + ": use either a point year or a range, not both");
        }
    }

    private static void requireEnum(
            String value,
            Set<String> allowed,
            String field,
            String path,
            List<String> errors) {
        if (value != null && !allowed.contains(value)) {
            errors.add(path + ": invalid " + field);
        }
    }

    private static boolean safeOfficialUrl(String value, String expectedHost) {
        if (expectedHost == null) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && expectedHost.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static void scanProhibited(JsonNode node, String path, List<String> errors) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().replace("_", "")
                        .replace("-", "").toLowerCase(Locale.ROOT);
                if (PROHIBITED_KEYS.contains(normalized)) {
                    errors.add(path + ": prohibited key " + field.getKey());
                }
                scanProhibited(field.getValue(), path + "." + field.getKey(), errors);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                scanProhibited(node.get(index), path + "[" + index + "]", errors);
            }
        }
    }

    public record ValidatedDataset(
            List<ForecastReference> records,
            List<String> errors) {
        public ValidatedDataset {
            records = List.copyOf(records);
            errors = List.copyOf(errors);
        }
    }
}
