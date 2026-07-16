package com.aienterprise.backend.tracker.governance;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict schema validator for the reference-only WP3.5 governance ledger. */
public final class GovernanceLedgerValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MIN_RECORDS = 6;
    private static final int MAX_RECORDS = 100;
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "recordId", "recordType", "jurisdiction", "subject", "status",
            "effectiveOn", "effectiveOnPrecision", "sourceCode", "sourceUrl",
            "accessedOn", "contentSha256", "publicationPath", "factSummary",
            "reviewStatus");
    private static final Set<String> RECORD_TYPES = Set.of(
            "TREATY_STATUS", "TREATY_ACTION", "LICENSE_FRAMEWORK",
            "REGULATORY_NOTICE");
    private static final Set<String> PRECISIONS = Set.of("DAY", "MONTH", "YEAR");
    private static final Map<String, String> SOURCE_HOSTS = Map.of(
            "UNOOSA", "www.unoosa.org",
            "FAA", "www.faa.gov",
            "GOVINFO", "www.govinfo.gov");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> PROHIBITED_KEYS = Set.of(
            "quote", "evidencequote", "body", "html", "pdf", "image", "binary",
            "nodecode", "claimedlevel", "score", "readiness", "eta", "etayear");

    public ValidatedDataset validate(byte[] json, Clock clock) {
        List<String> errors = new ArrayList<>();
        if (json == null || json.length == 0) {
            return new ValidatedDataset(List.of(), List.of("dataset: must not be empty"));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception malformed) {
            return new ValidatedDataset(List.of(),
                    List.of("dataset: malformed JSON: " + malformed.getMessage()));
        }
        if (root == null || !root.isArray()) {
            return new ValidatedDataset(List.of(),
                    List.of("dataset: top level must be an array"));
        }
        if (root.size() < MIN_RECORDS || root.size() > MAX_RECORDS) {
            errors.add("dataset: record count must be between 6 and 100");
        }

        List<GovernanceRecord> records = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        LocalDate today = LocalDate.now(clock);
        for (int index = 0; index < root.size(); index++) {
            JsonNode node = root.get(index);
            String path = "record[" + index + "]";
            int errorsBefore = errors.size();
            if (!node.isObject()) {
                errors.add(path + ": must be an object");
                continue;
            }
            scanProhibited(node, path, errors);
            node.fieldNames().forEachRemaining(key -> {
                if (!ALLOWED_KEYS.contains(key)) {
                    errors.add(path + ": unknown key " + key);
                }
            });

            String recordId = text(node, "recordId", path, 100, errors);
            String recordType = text(node, "recordType", path, 30, errors);
            String jurisdiction = text(node, "jurisdiction", path, 100, errors);
            String subject = text(node, "subject", path, 500, errors);
            String status = text(node, "status", path, 80, errors);
            LocalDate effectiveOn = date(node, "effectiveOn", path, errors);
            String precision = text(node, "effectiveOnPrecision", path, 10, errors);
            String sourceCode = text(node, "sourceCode", path, 30, errors);
            String sourceUrl = text(node, "sourceUrl", path, 1_000, errors);
            LocalDate accessedOn = date(node, "accessedOn", path, errors);
            String sha = text(node, "contentSha256", path, 64, errors);
            String publicationPath = text(node, "publicationPath", path, 20, errors);
            String factSummary = text(node, "factSummary", path, 1_000, errors);
            String reviewStatus = text(node, "reviewStatus", path, 20, errors);

            if (recordId != null && !ids.add(recordId)) {
                errors.add(path + ": duplicate recordId " + recordId);
            }
            if (recordType != null && !RECORD_TYPES.contains(recordType)) {
                errors.add(path + ": invalid recordType " + recordType);
            }
            if (precision != null && !PRECISIONS.contains(precision)) {
                errors.add(path + ": invalid effectiveOnPrecision " + precision);
            }
            if (sourceCode != null && !SOURCE_HOSTS.containsKey(sourceCode)) {
                errors.add(path + ": sourceCode must be UNOOSA, FAA, or GOVINFO");
            }
            if (sourceCode != null && sourceUrl != null
                    && !safeOfficialUrl(sourceUrl, SOURCE_HOSTS.get(sourceCode))) {
                errors.add(path + ": sourceUrl must use the source's official HTTPS host");
            }
            if (accessedOn != null && accessedOn.isAfter(today)) {
                errors.add(path + ": accessedOn must not be in the future");
            }
            if (sha != null && !SHA256.matcher(sha).matches()) {
                errors.add(path + ": contentSha256 must be 64 lowercase hex characters");
            }
            if (publicationPath != null && !"PRIMARY".equals(publicationPath)) {
                errors.add(path + ": publicationPath must be PRIMARY");
            }
            if (reviewStatus != null && !"HUMAN_REVIEWED".equals(reviewStatus)) {
                errors.add(path + ": reviewStatus must be HUMAN_REVIEWED");
            }
            if (factSummary != null && factSummary.length() < 40) {
                errors.add(path + ": factSummary must be at least 40 characters");
            }

            if (errors.size() == errorsBefore) {
                records.add(new GovernanceRecord(
                        recordId, recordType, jurisdiction, subject, status,
                        effectiveOn, precision, sourceCode, sourceUrl, accessedOn,
                        sha, publicationPath, factSummary, reviewStatus));
            }
        }
        return new ValidatedDataset(records, errors);
    }

    private static String text(
            JsonNode node,
            String field,
            String path,
            int maxLength,
            List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            errors.add(path + ": " + field + " must be nonblank text");
            return null;
        }
        String normalized = value.asText().trim().replaceAll("\\s+", " ");
        if (normalized.length() > maxLength) {
            errors.add(path + ": " + field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private static LocalDate date(
            JsonNode node, String field, String path, List<String> errors) {
        String raw = text(node, field, path, 10, errors);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException malformed) {
            errors.add(path + ": " + field + " must be an ISO date");
            return null;
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

    private static void scanProhibited(
            JsonNode node, String path, List<String> errors) {
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
            List<GovernanceRecord> records,
            List<String> errors) {
        public ValidatedDataset {
            records = List.copyOf(records);
            errors = List.copyOf(errors);
        }
    }
}
