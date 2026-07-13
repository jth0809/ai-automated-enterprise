package com.aienterprise.backend.tracker.backfill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class HistoricalCorpusValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of(
            "DISCOVERED", "READY_FOR_MAPPING", "REJECTED");
    private static final Set<String> PUBLICATION_PATHS = Set.of(
            "PRIMARY", "THIRD_PARTY", "WIRE_REPRINT");
    private static final Set<String> DATE_PRECISIONS = Set.of(
            "DAY", "MONTH", "YEAR");
    private static final Set<String> PROHIBITED_KEYS = Set.of(
            "quote", "evidencequote", "excerpt", "body", "html", "sourcetitle");
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "candidateId", "eventTitle", "candidateTopics", "actor", "occurredOn",
            "occurredOnPrecision", "evidence", "discoveryStatus", "discoveryNote");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "sourceCode", "url", "locator", "accessedOn", "contentSha256",
            "publicationPath", "factSummary");
    private static final Pattern CANDIDATE_ID = Pattern.compile("HC-[A-Z0-9-]+");
    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]*");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public CorpusReport validate(Resource resource) {
        List<String> errors = new ArrayList<>();
        Set<String> candidateIds = new HashSet<>();
        int totalCount = 0;
        int readyCount = 0;
        int rejectedCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                totalCount++;
                if (line.getBytes(StandardCharsets.UTF_8).length > 8192) {
                    error(errors, lineNumber, "line exceeds 8192 UTF-8 bytes");
                    continue;
                }
                JsonNode candidate;
                try {
                    candidate = JSON.readTree(line);
                } catch (JsonProcessingException e) {
                    error(errors, lineNumber, "invalid JSON");
                    continue;
                }
                if (candidate == null || !candidate.isObject()) {
                    error(errors, lineNumber, "candidate must be a JSON object");
                    continue;
                }

                findProhibitedKeys(candidate, "$", lineNumber, errors);
                rejectUnknownFields(candidate, CANDIDATE_FIELDS, "candidate", lineNumber, errors);
                String candidateId = requiredText(candidate, "candidateId", lineNumber, errors);
                if (candidateId != null) {
                    if (!CANDIDATE_ID.matcher(candidateId).matches()) {
                        error(errors, lineNumber, "invalid candidateId");
                    } else if (!candidateIds.add(candidateId)) {
                        error(errors, lineNumber, "duplicate candidateId " + candidateId);
                    }
                }

                String eventTitle = boundedText(candidate, "eventTitle", 200, lineNumber, errors);
                List<String> topics = candidateTopics(candidate, lineNumber, errors);
                String actor = boundedText(candidate, "actor", 200, lineNumber, errors);
                LocalDate occurredOn = date(candidate, "occurredOn", lineNumber, errors);
                String occurredOnPrecision = requiredText(
                        candidate, "occurredOnPrecision", lineNumber, errors);
                validateOccurredOnPrecision(
                        occurredOn, occurredOnPrecision, lineNumber, errors);
                List<HistoricalEvidenceReference> evidence = evidence(candidate, lineNumber, errors);
                String status = requiredText(candidate, "discoveryStatus", lineNumber, errors);
                String discoveryNote = boundedText(candidate, "discoveryNote", 1000, lineNumber, errors);

                if (status != null && !STATUSES.contains(status)) {
                    error(errors, lineNumber, "invalid discoveryStatus " + status);
                } else if ("READY_FOR_MAPPING".equals(status)) {
                    readyCount++;
                } else if ("REJECTED".equals(status)) {
                    rejectedCount++;
                    if (discoveryNote == null) {
                        error(errors, lineNumber, "REJECTED candidate requires discoveryNote");
                    }
                }

                if (candidateId != null && eventTitle != null && actor != null
                        && occurredOn != null && occurredOnPrecision != null
                        && status != null && discoveryNote != null
                        && !topics.isEmpty() && !evidence.isEmpty()) {
                    new HistoricalCandidate(
                            candidateId,
                            eventTitle,
                            topics,
                            actor,
                            occurredOn,
                            occurredOnPrecision,
                            evidence,
                            status,
                            discoveryNote);
                }
            }
        } catch (IOException e) {
            errors.add("cannot read corpus resource: " + e.getClass().getSimpleName());
        }

        return new CorpusReport(totalCount, readyCount, rejectedCount, errors);
    }

    private static void validateOccurredOnPrecision(
            LocalDate occurredOn,
            String precision,
            int lineNumber,
            List<String> errors) {
        if (precision == null) {
            return;
        }
        if (!DATE_PRECISIONS.contains(precision)) {
            error(errors, lineNumber, "invalid occurredOnPrecision " + precision);
            return;
        }
        if (occurredOn == null) {
            return;
        }
        if ("MONTH".equals(precision) && occurredOn.getDayOfMonth() != 1) {
            error(errors, lineNumber, "MONTH precision requires day 1");
        }
        if ("YEAR".equals(precision)
                && (occurredOn.getMonthValue() != 1 || occurredOn.getDayOfMonth() != 1)) {
            error(errors, lineNumber, "YEAR precision requires January 1");
        }
    }

    private static List<String> candidateTopics(JsonNode candidate, int lineNumber, List<String> errors) {
        JsonNode value = candidate.get("candidateTopics");
        if (value == null || !value.isArray() || value.isEmpty()) {
            error(errors, lineNumber, "candidateTopics must not be empty");
            return List.of();
        }
        if (value.size() > 20) {
            error(errors, lineNumber, "candidateTopics exceeds 20 entries");
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode topic : value) {
            if (!topic.isTextual() || topic.asText().isBlank()) {
                error(errors, lineNumber, "candidateTopics entries must be nonblank strings");
                continue;
            }
            if (topic.asText().length() > 100) {
                error(errors, lineNumber, "candidate topic exceeds 100 characters");
                continue;
            }
            topics.add(topic.asText().trim());
        }
        return topics;
    }

    private static List<HistoricalEvidenceReference> evidence(
            JsonNode candidate, int lineNumber, List<String> errors) {
        JsonNode value = candidate.get("evidence");
        if (value == null || !value.isArray() || value.isEmpty()) {
            error(errors, lineNumber, "evidence must not be empty");
            return List.of();
        }
        if (value.size() > 8) {
            error(errors, lineNumber, "evidence exceeds 8 entries");
        }

        List<HistoricalEvidenceReference> references = new ArrayList<>();
        int index = 0;
        for (JsonNode item : value) {
            index++;
            if (!item.isObject()) {
                error(errors, lineNumber, "evidence[" + index + "] must be an object");
                continue;
            }
            rejectUnknownFields(item, EVIDENCE_FIELDS, "evidence", lineNumber, errors);
            String sourceCode = requiredText(item, "sourceCode", lineNumber, errors);
            if (sourceCode != null && !SOURCE_CODE.matcher(sourceCode).matches()) {
                error(errors, lineNumber, "invalid sourceCode " + sourceCode);
            }
            URI url = httpsUri(item, lineNumber, errors);
            String locator = boundedText(item, "locator", 300, lineNumber, errors);
            LocalDate accessedOn = date(item, "accessedOn", lineNumber, errors);
            String contentSha256 = requiredText(item, "contentSha256", lineNumber, errors);
            if (contentSha256 != null && !SHA256.matcher(contentSha256).matches()) {
                error(errors, lineNumber, "contentSha256 must be 64 lowercase hexadecimal characters");
            }
            String publicationPath = requiredText(item, "publicationPath", lineNumber, errors);
            if (publicationPath != null && !PUBLICATION_PATHS.contains(publicationPath)) {
                error(errors, lineNumber, "invalid publicationPath " + publicationPath);
            }
            String factSummary = boundedText(item, "factSummary", 500, lineNumber, errors);

            if (sourceCode != null && url != null && locator != null && accessedOn != null
                    && contentSha256 != null && publicationPath != null && factSummary != null) {
                references.add(new HistoricalEvidenceReference(
                        sourceCode, url, locator, accessedOn, contentSha256,
                        publicationPath, factSummary));
            }
        }
        return references;
    }

    private static URI httpsUri(JsonNode evidence, int lineNumber, List<String> errors) {
        String raw = requiredText(evidence, "url", lineNumber, errors);
        if (raw == null) {
            return null;
        }
        if (raw.length() > 1000) {
            error(errors, lineNumber, "URL exceeds 1000 characters");
            return null;
        }
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                error(errors, lineNumber, "HTTPS URL required");
                return null;
            }
            if (uri.getUserInfo() != null) {
                error(errors, lineNumber, "URL credentials are prohibited");
                return null;
            }
            if (hasSensitiveQuery(uri.getRawQuery())) {
                error(errors, lineNumber, "URL query contains sensitive parameter");
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            error(errors, lineNumber, "invalid URL");
            return null;
        }
    }

    private static LocalDate date(JsonNode object, String field, int lineNumber, List<String> errors) {
        String raw = requiredText(object, field, lineNumber, errors);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            error(errors, lineNumber, "invalid " + field);
            return null;
        }
    }

    private static String boundedText(
            JsonNode object, String field, int maxLength, int lineNumber, List<String> errors) {
        String value = requiredText(object, field, lineNumber, errors);
        if (value != null && value.length() > maxLength) {
            error(errors, lineNumber, field + " exceeds " + maxLength + " characters");
            return null;
        }
        return value;
    }

    private static String requiredText(
            JsonNode object, String field, int lineNumber, List<String> errors) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            error(errors, lineNumber, field + " must not be blank");
            return null;
        }
        return value.asText().trim();
    }

    private static void findProhibitedKeys(
            JsonNode node, String path, int lineNumber, List<String> errors) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (PROHIBITED_KEYS.contains(normalizedKey(name))) {
                    error(errors, lineNumber, "prohibited field " + path + "." + name);
                }
                findProhibitedKeys(node.get(name), path + "." + name, lineNumber, errors);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                findProhibitedKeys(child, path + "[" + index + "]", lineNumber, errors);
                index++;
            }
        }
    }

    private static String normalizedKey(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean hasSensitiveQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return false;
        }
        for (String pair : rawQuery.split("&")) {
            String rawName = pair.split("=", 2)[0];
            String name;
            try {
                name = normalizedKey(URLDecoder.decode(rawName, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                return true;
            }
            if (name.equals("auth") || name.equals("apikey")
                    || name.contains("token") || name.contains("secret")
                    || name.contains("signature") || name.contains("password")
                    || name.contains("cookie")) {
                return true;
            }
        }
        return false;
    }

    private static void rejectUnknownFields(
            JsonNode object,
            Set<String> allowedFields,
            String objectName,
            int lineNumber,
            List<String> errors) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowedFields.contains(name)) {
                error(errors, lineNumber, "unknown " + objectName + " field " + name);
            }
        }
    }

    private static void error(List<String> errors, int lineNumber, String message) {
        errors.add("line " + lineNumber + ": " + message);
    }
}
