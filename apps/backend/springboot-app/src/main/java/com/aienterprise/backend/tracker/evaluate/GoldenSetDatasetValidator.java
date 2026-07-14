package com.aienterprise.backend.tracker.evaluate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class GoldenSetDatasetValidator {

    static final int MAX_FILE_BYTES = 256 * 1024;
    static final int MAX_BODY_BYTES = 2_000;
    static final int MAX_EXPECTED_OUTPUT_BYTES = 2_000;
    static final int EXPECTED_ACTIVE_CASES = 50;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = Set.of(
            "datasetVersion", "nodeSetVersion", "rubricVersion",
            "expectedSchemaVersion", "cases");
    private static final Set<String> CASE_FIELDS = Set.of(
            "caseCode", "fixtureKind", "title", "body", "expectedOutput",
            "notes", "provenanceRefs", "scenarioTags", "pairCode",
            "pairExpectation", "active");
    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "relevant", "nodeCode", "eventType", "claimedLevel", "actor",
            "occurredOn", "publicationPath");
    private static final Set<String> FIXTURE_KINDS = Set.of(
            "SYNTHETIC", "HUMAN_PARAPHRASE");
    private static final Set<String> EVENT_TYPES = Set.of(
            "THEORY_PAPER", "LAB_RESULT", "PROTOTYPE_DEMO", "FLIGHT_TEST",
            "OPERATIONAL_DEPLOYMENT", "COMMERCIALIZATION", "INSTITUTIONAL_ADVANCE",
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY",
            "RETROSPECTIVE", "ROLLBACK");
    private static final Set<String> NON_STATE_EVENTS = Set.of(
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY", "RETROSPECTIVE");
    private static final Set<String> PUBLICATION_PATHS = Set.of(
            "PRIMARY", "THIRD_PARTY", "WIRE_REPRINT");
    private static final Set<String> PROHIBITED_FIELDS = Set.of(
                "url", "uri", "html", "pdf", "quote", "evidencequote", "excerpt", "sourcebody",
            "articlebody", "rawmodeloutput", "modelresponse", "attachment", "warc");

    private final Set<String> approvedProvenanceRefs;
    private final Set<String> nodeCodes;
    private final int expectedActiveCases;

    public GoldenSetDatasetValidator() {
        this(loadFieldSet("tracker/backfill-v1.json", "backfillId"),
                loadFieldSet("tracker/backfill-audit-v1.json", "nodeCode"),
                EXPECTED_ACTIVE_CASES);
    }

    GoldenSetDatasetValidator(
            Set<String> approvedProvenanceRefs,
            Set<String> nodeCodes,
            int expectedActiveCases) {
        this.approvedProvenanceRefs = Set.copyOf(approvedProvenanceRefs);
        this.nodeCodes = Set.copyOf(nodeCodes);
        if (expectedActiveCases < 1 || expectedActiveCases > 60) {
            throw new IllegalArgumentException("expectedActiveCases must be between 1 and 60");
        }
        this.expectedActiveCases = expectedActiveCases;
    }

    public ValidationResult validate(Resource resource) {
        List<String> errors = new ArrayList<>();
        byte[] bytes;
        try {
            bytes = resource.getInputStream().readAllBytes();
        } catch (IOException e) {
            return ValidationResult.invalid("cannot read golden dataset");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            return ValidationResult.invalid(
                    "golden dataset file exceeds " + MAX_FILE_BYTES + " bytes");
        }

        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException e) {
            return ValidationResult.invalid("golden dataset is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            return ValidationResult.invalid("golden dataset root must be an object");
        }
        requireExactFields(root, ROOT_FIELDS, "root", errors);

        String datasetVersion = boundedText(root, "datasetVersion", 80, "root", errors);
        String nodeSetVersion = boundedText(root, "nodeSetVersion", 40, "root", errors);
        String rubricVersion = boundedText(root, "rubricVersion", 40, "root", errors);
        String expectedSchemaVersion = boundedText(
                root, "expectedSchemaVersion", 40, "root", errors);
        if (!"nodes-v1.0".equals(nodeSetVersion)) {
            errors.add("root: nodeSetVersion must be nodes-v1.0");
        }
        if (!"r2.0".equals(rubricVersion)) {
            errors.add("root: rubricVersion must be r2.0");
        }
        if (!"golden-output-v1".equals(expectedSchemaVersion)) {
            errors.add("root: expectedSchemaVersion must be golden-output-v1");
        }

        JsonNode rawCases = root.get("cases");
        if (rawCases == null || !rawCases.isArray()) {
            errors.add("root: cases must be an array");
            rawCases = JSON.createArrayNode();
        }

        List<GoldenCase> cases = new ArrayList<>();
        Set<String> caseCodes = new HashSet<>();
        int index = 0;
        for (JsonNode rawCase : rawCases) {
            String location = "cases[" + index++ + "]";
            GoldenCase parsed = validateCase(rawCase, location, caseCodes, errors);
            if (parsed != null) {
                cases.add(parsed);
            }
        }
        long activeCases = cases.stream().filter(GoldenCase::active).count();
        if (activeCases != expectedActiveCases) {
            errors.add("root: expected exactly " + expectedActiveCases
                    + " active cases but found " + activeCases);
        }
        if (cases.size() > 60) {
            errors.add("root: cases must not exceed 60 items");
        }

        String datasetSha256;
        try {
            datasetSha256 = sha256(JSON.writeValueAsBytes(canonicalize(root)));
        } catch (IOException e) {
            errors.add("root: cannot canonicalize dataset");
            datasetSha256 = "";
        }
        return new ValidationResult(
                datasetVersion, nodeSetVersion, rubricVersion, expectedSchemaVersion,
                datasetSha256, List.copyOf(cases), List.copyOf(errors));
    }

    private GoldenCase validateCase(
            JsonNode item,
            String location,
            Set<String> caseCodes,
            List<String> errors) {
        if (item == null || !item.isObject()) {
            errors.add(location + ": case must be an object");
            return null;
        }
        requireExactFields(item, CASE_FIELDS, location, errors);
        scanForbidden(item, location, errors);

        String caseCode = boundedText(item, "caseCode", 80, location, errors);
        if (caseCode != null && !caseCode.matches("[A-Z0-9][A-Z0-9-]{2,79}")) {
            errors.add(location + ": invalid caseCode");
        }
        if (caseCode != null && !caseCodes.add(caseCode)) {
            errors.add(location + ": duplicate caseCode " + caseCode);
        }
        String fixtureKind = boundedText(item, "fixtureKind", 24, location, errors);
        if (!FIXTURE_KINDS.contains(fixtureKind)) {
            errors.add(location + ": fixtureKind must be SYNTHETIC or HUMAN_PARAPHRASE");
        }
        String title = boundedText(item, "title", 300, location, errors);
        String body = requiredText(item, "body", location, errors);
        if (body != null && utf8Length(body) > MAX_BODY_BYTES) {
            errors.add(location + ": body exceeds 2000 UTF-8 bytes");
        }
        String notes = optionalText(item, "notes", 1_000, location, errors);
        boolean active = booleanValue(item, "active", location, errors);

        List<String> provenanceRefs = stringArray(
                item.get("provenanceRefs"), location + ".provenanceRefs", 1, 8, errors);
        for (String ref : provenanceRefs) {
            if (!approvedProvenanceRefs.contains(ref)) {
                errors.add(location + ": unknown provenance ref " + ref);
            }
        }
        List<String> scenarioTags = stringArray(
                item.get("scenarioTags"), location + ".scenarioTags", 1, 12, errors);
        for (String tag : scenarioTags) {
            if (!tag.matches("[A-Z0-9][A-Z0-9_-]{0,39}")) {
                errors.add(location + ": invalid scenario tag " + tag);
            }
        }

        String pairCode = nullableBoundedText(item, "pairCode", 80, location, errors);
        String pairExpectation = nullableBoundedText(
                item, "pairExpectation", 12, location, errors);
        if ((pairCode == null) != (pairExpectation == null)) {
            errors.add(location + ": pairCode and pairExpectation must both be null or present");
        }
        if (pairExpectation != null
                && !Set.of("DUPLICATE", "DISTINCT").contains(pairExpectation)) {
            errors.add(location + ": pairExpectation must be DUPLICATE or DISTINCT");
        }

        JsonNode expectedOutput = item.get("expectedOutput");
        validateExpectedOutput(expectedOutput, location + ".expectedOutput", errors);
        String expectedOutputJson = "";
        if (expectedOutput != null) {
            try {
                expectedOutputJson = JSON.writeValueAsString(canonicalize(expectedOutput));
                if (utf8Length(expectedOutputJson) > MAX_EXPECTED_OUTPUT_BYTES) {
                    errors.add(location + ": expectedOutput exceeds 2000 UTF-8 bytes");
                }
            } catch (IOException e) {
                errors.add(location + ": cannot canonicalize expectedOutput");
            }
        }

        String inputSha256 = sha256(String.join("\n",
                fixtureKind == null ? "" : fixtureKind,
                title == null ? "" : title,
                body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        return new GoldenCase(
                caseCode, fixtureKind, title, body,
                expectedOutput == null ? JSON.nullNode() : expectedOutput.deepCopy(),
                expectedOutputJson, notes, List.copyOf(provenanceRefs),
                Set.copyOf(new LinkedHashSet<>(scenarioTags)), pairCode,
                pairExpectation, active, inputSha256);
    }

    private void validateExpectedOutput(
            JsonNode output, String location, List<String> errors) {
        if (output == null || !output.isObject()) {
            errors.add(location + ": expectedOutput must be an object");
            return;
        }
        Set<String> actualFields = fieldNames(output);
        if (!actualFields.equals(EXPECTED_FIELDS)) {
            errors.add(location + ": expectedOutput fields must be exactly " + EXPECTED_FIELDS);
        }
        JsonNode relevantNode = output.get("relevant");
        if (relevantNode == null || !relevantNode.isBoolean()) {
            errors.add(location + ": relevant must be boolean");
            return;
        }
        boolean relevant = relevantNode.asBoolean();
        if (!relevant) {
            for (String field : EXPECTED_FIELDS) {
                if (!"relevant".equals(field)
                        && output.has(field) && !output.get(field).isNull()) {
                    errors.add(location
                            + ": irrelevant expectedOutput requires null semantic fields");
                    break;
                }
            }
            return;
        }

        String nodeCode = text(output, "nodeCode");
        if (!nodeCodes.contains(nodeCode)) {
            errors.add(location + ": unknown nodeCode " + nodeCode);
        }
        String eventType = text(output, "eventType");
        if (!EVENT_TYPES.contains(eventType)) {
            errors.add(location + ": unknown eventType " + eventType);
        }
        JsonNode level = output.get("claimedLevel");
        if (NON_STATE_EVENTS.contains(eventType)) {
            if (level != null && !level.isNull()) {
                errors.add(location + ": non-state event requires null claimedLevel");
            }
        } else if (level == null || !level.isIntegralNumber()
                || level.asInt() < 1 || level.asInt() > 9) {
            errors.add(location + ": claimedLevel must be between 1 and 9");
        }
        String actor = text(output, "actor");
        if (actor == null || actor.isBlank() || actor.length() > 200) {
            errors.add(location + ": actor must be 1..200 characters");
        }
        String occurredOn = text(output, "occurredOn");
        try {
            LocalDate.parse(occurredOn);
        } catch (DateTimeParseException | NullPointerException invalidDate) {
            errors.add(location + ": occurredOn must be ISO date");
        }
        String publicationPath = text(output, "publicationPath");
        if (!PUBLICATION_PATHS.contains(publicationPath)) {
            errors.add(location + ": invalid publicationPath");
        }
    }

    private static void scanForbidden(
            JsonNode node, String location, List<String> errors) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String normalized = entry.getKey().toLowerCase(Locale.ROOT)
                        .replace("_", "").replace("-", "");
                if (PROHIBITED_FIELDS.contains(normalized)) {
                    errors.add(location + ": forbidden field " + entry.getKey());
                }
                scanForbidden(entry.getValue(), location + "." + entry.getKey(), errors);
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                scanForbidden(child, location, errors);
            }
        } else if (node.isTextual()) {
            String value = node.asText().toLowerCase(Locale.ROOT);
            if (value.contains("http://") || value.contains("https://")) {
                errors.add(location + ": URL-like content is forbidden");
            }
            if (value.contains("<html") || value.contains("<!doctype")) {
                errors.add(location + ": HTML-like content is forbidden");
            }
        }
    }

    private static void requireExactFields(
            JsonNode node, Set<String> expected, String location, List<String> errors) {
        Set<String> actual = fieldNames(node);
        if (!actual.equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new HashSet<>(actual);
            extra.removeAll(expected);
            if (!missing.isEmpty()) {
                errors.add(location + ": missing fields " + missing);
            }
            for (String field : extra) {
                String normalized = field.toLowerCase(Locale.ROOT)
                        .replace("_", "").replace("-", "");
                if (PROHIBITED_FIELDS.contains(normalized)) {
                    errors.add(location + ": forbidden field " + field);
                } else {
                    errors.add(location + ": unexpected field " + field);
                }
            }
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> result = new HashSet<>();
        if (node != null && node.isObject()) {
            node.fieldNames().forEachRemaining(result::add);
        }
        return result;
    }

    private static String boundedText(
            JsonNode node, String field, int max, String location, List<String> errors) {
        String value = requiredText(node, field, location, errors);
        if (value != null && value.length() > max) {
            errors.add(location + ": " + field + " exceeds " + max + " characters");
        }
        return value;
    }

    private static String nullableBoundedText(
            JsonNode node, String field, int max, String location, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add(location + ": " + field + " must be null or nonblank text");
            return null;
        }
        if (value.asText().length() > max) {
            errors.add(location + ": " + field + " exceeds " + max + " characters");
        }
        return value.asText();
    }

    private static String optionalText(
            JsonNode node, String field, int max, String location, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(location + ": " + field + " must be text or null");
            return null;
        }
        if (value.asText().length() > max) {
            errors.add(location + ": " + field + " exceeds " + max + " characters");
        }
        return value.asText();
    }

    private static String requiredText(
            JsonNode node, String field, String location, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            errors.add(location + ": " + field + " must be nonblank text");
            return null;
        }
        return value.asText();
    }

    private static boolean booleanValue(
            JsonNode node, String field, String location, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            errors.add(location + ": " + field + " must be boolean");
            return false;
        }
        return value.asBoolean();
    }

    private static List<String> stringArray(
            JsonNode node,
            String location,
            int min,
            int max,
            List<String> errors) {
        if (node == null || !node.isArray()) {
            errors.add(location + " must be an array");
            return List.of();
        }
        if (node.size() < min || node.size() > max) {
            errors.add(location + " must contain " + min + ".." + max + " values");
        }
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 80) {
                errors.add(location + " values must be nonblank text up to 80 characters");
                continue;
            }
            if (!unique.add(value.asText())) {
                errors.add(location + " contains duplicate " + value.asText());
                continue;
            }
            values.add(value.asText());
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                result.set(name, canonicalize(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            for (JsonNode child : node) {
                result.add(canonicalize(child));
            }
            return result;
        }
        return node.deepCopy();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Set<String> loadFieldSet(String path, String field) {
        try {
            JsonNode root = JSON.readTree(new ClassPathResource(path).getInputStream());
            Set<String> values = new HashSet<>();
            if (root != null && root.isArray()) {
                for (JsonNode item : root) {
                    JsonNode value = item.get(field);
                    if (value != null && value.isTextual()) {
                        values.add(value.asText());
                    }
                }
            }
            if (values.isEmpty()) {
                throw new IllegalStateException("no " + field + " values in " + path);
            }
            return Set.copyOf(values);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    public record GoldenCase(
            String caseCode,
            String fixtureKind,
            String title,
            String body,
            JsonNode expectedOutput,
            String expectedOutputJson,
            String notes,
            List<String> provenanceRefs,
            Set<String> scenarioTags,
            String pairCode,
            String pairExpectation,
            boolean active,
            String inputSha256) {
    }

    public record ValidationResult(
            String datasetVersion,
            String nodeSetVersion,
            String rubricVersion,
            String expectedSchemaVersion,
            String datasetSha256,
            List<GoldenCase> cases,
            List<String> errors) {

        static ValidationResult invalid(String error) {
            return new ValidationResult(
                    null, null, null, null, "", List.of(), List.of(error));
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
