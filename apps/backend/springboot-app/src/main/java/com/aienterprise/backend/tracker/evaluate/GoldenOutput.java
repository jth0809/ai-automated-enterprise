package com.aienterprise.backend.tracker.evaluate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record GoldenOutput(
        boolean relevant,
        String nodeCode,
        String eventType,
        Integer claimedLevel,
        String actor,
        LocalDate occurredOn,
        String publicationPath,
        String evidenceQuote) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Set<String> EVENT_TYPES = Set.of(
            "THEORY_PAPER", "LAB_RESULT", "PROTOTYPE_DEMO", "FLIGHT_TEST",
            "OPERATIONAL_DEPLOYMENT", "COMMERCIALIZATION", "INSTITUTIONAL_ADVANCE",
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY",
            "RETROSPECTIVE", "ROLLBACK");
    private static final Set<String> NON_STATE_EVENTS = Set.of(
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY", "RETROSPECTIVE");
    private static final Set<String> PUBLICATION_PATHS = Set.of(
            "PRIMARY", "THIRD_PARTY", "WIRE_REPRINT");

    public static GoldenOutput fromExpectedJson(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            return new GoldenOutput(
                    node.path("relevant").asBoolean(),
                    nullableText(node.get("nodeCode")),
                    nullableText(node.get("eventType")),
                    nullableInteger(node.get("claimedLevel")),
                    nullableText(node.get("actor")),
                    nullableDate(node.get("occurredOn")),
                    nullableText(node.get("publicationPath")),
                    null);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid expected golden output", e);
        }
    }

    public Set<String> diff(GoldenOutput actual) {
        LinkedHashSet<String> differences = new LinkedHashSet<>();
        if (actual == null || relevant != actual.relevant) {
            differences.add("relevant");
        }
        if (actual == null || !Objects.equals(nodeCode, actual.nodeCode)) {
            differences.add("nodeCode");
        }
        if (actual == null || !Objects.equals(eventType, actual.eventType)) {
            differences.add("eventType");
        }
        if (actual == null || !Objects.equals(claimedLevel, actual.claimedLevel)) {
            differences.add("claimedLevel");
        }
        if (actual == null || !Objects.equals(actor, actual.actor)) {
            differences.add("actor");
        }
        if (actual == null || !Objects.equals(occurredOn, actual.occurredOn)) {
            differences.add("occurredOn");
        }
        if (actual == null || !Objects.equals(publicationPath, actual.publicationPath)) {
            differences.add("publicationPath");
        }
        return Set.copyOf(differences);
    }

    public String validationError(GoldenInput input) {
        if (!relevant) {
            if (nodeCode != null || eventType != null || claimedLevel != null
                    || actor != null || occurredOn != null || publicationPath != null
                    || (evidenceQuote != null && !evidenceQuote.isBlank())) {
                return "SCHEMA_INVALID";
            }
            return null;
        }
        boolean stateLevelValid = NON_STATE_EVENTS.contains(eventType)
                ? claimedLevel == null
                : claimedLevel != null && claimedLevel >= 1 && claimedLevel <= 9;
        if (nodeCode == null || !nodeCode.matches("P[1-6]-[A-Z0-9-]{2,60}")
                || !EVENT_TYPES.contains(eventType)
                || !stateLevelValid
                || actor == null || actor.isBlank() || actor.length() > 200
                || occurredOn == null
                || !PUBLICATION_PATHS.contains(publicationPath)
                || evidenceQuote == null || evidenceQuote.isBlank()) {
            return "SCHEMA_INVALID";
        }
        String normalizedBody = normalize(input.body());
        String normalizedQuote = normalize(evidenceQuote);
        if (normalizedBody == null || normalizedQuote == null
                || !normalizedBody.contains(normalizedQuote)) {
            return "QUOTE_MISMATCH";
        }
        return null;
    }

    public String canonicalSemanticJson() {
        ObjectNode node = semanticNode();
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Cannot canonicalize golden output", impossible);
        }
    }

    public String canonicalOutputSha256() {
        ObjectNode node = semanticNode();
        if (evidenceQuote == null) {
            node.putNull("evidenceQuote");
        } else {
            node.put("evidenceQuote", evidenceQuote);
        }
        try {
            return sha256(JSON.writeValueAsBytes(node));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Cannot hash golden output", impossible);
        }
    }

    private ObjectNode semanticNode() {
        ObjectNode node = JSON.createObjectNode();
        node.put("relevant", relevant);
        putNullable(node, "nodeCode", nodeCode);
        putNullable(node, "eventType", eventType);
        if (claimedLevel == null) {
            node.putNull("claimedLevel");
        } else {
            node.put("claimedLevel", claimedLevel);
        }
        putNullable(node, "actor", actor);
        putNullable(node, "occurredOn", occurredOn == null ? null : occurredOn.toString());
        putNullable(node, "publicationPath", publicationPath);
        return node;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Integer nullableInteger(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static LocalDate nullableDate(JsonNode node) {
        return node == null || node.isNull() ? null : LocalDate.parse(node.asText());
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return WHITESPACE.matcher(value).replaceAll(" ").trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
