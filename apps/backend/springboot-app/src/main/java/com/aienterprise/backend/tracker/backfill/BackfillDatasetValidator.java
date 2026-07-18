package com.aienterprise.backend.tracker.backfill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.aienterprise.backend.tracker.event.SourceEvidence;
import com.aienterprise.backend.tracker.event.VerificationDeriver;
import com.aienterprise.backend.tracker.event.VerificationLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class BackfillDatasetValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE_SET_VERSION = "nodes-v1.0";
    private static final String RUBRIC_VERSION = "r2.0";
    private static final long MAX_CANDIDATE_BYTES = 2L * 1024 * 1024;
    private static final long MAX_MAPPING_BYTES = 1024L * 1024;
    private static final int MAX_CANDIDATES = 512;
    private static final int MAX_MAPPINGS = 512;
    private static final int MAX_STATE_CLAIMS_PER_NODE = 32;
    private static final Pattern BACKFILL_ID = Pattern.compile("BF-[A-Z0-9-]{1,77}");
    private static final Set<String> DATE_PRECISIONS = Set.of("DAY", "MONTH", "YEAR");
    private static final Set<String> EVENT_TYPES = Set.of(
            "THEORY_PAPER", "LAB_RESULT", "PROTOTYPE_DEMO", "FLIGHT_TEST",
            "OPERATIONAL_DEPLOYMENT", "COMMERCIALIZATION", "INSTITUTIONAL_ADVANCE",
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY",
            "RETROSPECTIVE", "ROLLBACK");
    private static final Set<String> NON_PROGRESS_EVENTS = Set.of(
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY", "RETROSPECTIVE");
    private static final Set<String> NODE_CODES = Set.of(
            "P1-REUSE-LV", "P1-ORBIT-REFUEL", "P1-DEEP-PROP", "P1-EDL-HEAVY",
            "P1-SURFACE-ASCENT", "P1-CREW-SAFE", "P1-ORBIT-LOGISTICS",
            "P1-TRANSPORT-INTEGRATION",
            "P2-ECLSS", "P2-FOOD", "P2-RAD", "P2-MED", "P2-WASTE-CYCLE",
            "P2-HEALTH-AUTONOMY", "P2-SURVIVAL-INTEGRATION",
            "P3-CONSTRUCT", "P3-POWER", "P3-COMMS", "P3-THERMAL", "P3-DUST",
            "P3-HABITAT-INTEGRATION",
            "P4-ISRU-PROP", "P4-NUKE", "P4-MATERIALS", "P4-MANUFACTURING",
            "P4-RESOURCE-INTEGRATION",
            "P5-AUTOCON", "P5-AUTONOMY", "P5-MAINTENANCE", "P5-OPS-INTEGRATION",
            "P6-LAUNCH-MARKET", "P6-GOV-FRAMEWORK", "P6-FUNDING",
            "P6-INSURANCE-STD", "P6-SETTLEMENT-INTEGRATION");
    private static final Set<String> MAPPING_FIELDS = Set.of(
            "backfillId", "candidateId", "nodeSetVersion", "rubricVersion", "nodeCode",
            "eventType", "claimedLevel", "actor", "occurredOn", "occurredOnPrecision",
            "expectedVerificationLevel", "eventTitle", "rubricJustification",
            "programEndEffect", "programEndScope", "evidenceRefs", "review");
    private static final Set<String> REVIEW_FIELDS = Set.of("fact", "rubric", "reviewerNote");
    private static final Set<String> PROHIBITED_KEYS = Set.of(
            "quote", "evidencequote", "excerpt", "body", "bodyhtml", "bodytext",
            "sourcebody", "html", "sourcetitle", "attachment", "attachments",
            "pdf", "image");

    private final Resource sourceCatalog;
    private final boolean productionMode;

    public BackfillDatasetValidator() {
        this(new ClassPathResource("tracker/historical-source-catalog-v1.json"), true);
    }

    public BackfillDatasetValidator(Resource sourceCatalog) {
        this(sourceCatalog, false);
    }

    public BackfillDatasetValidator(boolean productionMode) {
        this(new ClassPathResource("tracker/historical-source-catalog-v1.json"),
                productionMode);
    }

    private BackfillDatasetValidator(
            Resource sourceCatalog, boolean productionMode) {
        this.sourceCatalog = sourceCatalog;
        this.productionMode = productionMode;
    }

    public ValidatedBackfill validate(Resource candidatesResource, Resource mappingsResource) {
        List<String> errors = new ArrayList<>();
        if (!withinSize(candidatesResource, MAX_CANDIDATE_BYTES,
                "candidate resource", errors)
                || !withinSize(mappingsResource, MAX_MAPPING_BYTES,
                        "mapping resource", errors)) {
            return new ValidatedBackfill(List.of(), Map.of(), errors, 0);
        }

        CorpusReport corpusReport = new HistoricalCorpusValidator().validate(candidatesResource);
        corpusReport.errors().forEach(error -> errors.add("candidates: " + error));
        if (corpusReport.totalCount() > MAX_CANDIDATES) {
            errors.add("candidates: corpus exceeds " + MAX_CANDIDATES + " entries");
        }
        if (productionMode) {
            if (corpusReport.readyCount() != corpusReport.totalCount()) {
                errors.add("candidates: production corpus must contain only READY candidates");
            }
            if (corpusReport.rejectedCount() != 0) {
                errors.add("candidates: production corpus must contain zero REJECTED candidates");
            }
        }

        Map<String, HistoricalCandidate> allCandidates = readCandidates(candidatesResource, errors);
        Map<String, SourceMetadata> sources = readSourceCatalog(errors);
        List<BackfillClaim> claims = new ArrayList<>();
        Map<String, HistoricalCandidate> usedCandidates = new LinkedHashMap<>();
        Set<String> backfillIds = new HashSet<>();
        Set<String> logicalClaims = new HashSet<>();

        JsonNode mappings = readJson(mappingsResource, "mappings", errors);
        if (mappings == null || !mappings.isArray()) {
            if (mappings != null) {
                errors.add("mappings: root must be a JSON array");
            }
            return new ValidatedBackfill(
                    claims, usedCandidates, errors, corpusReport.totalCount());
        }
        if (mappings.size() > MAX_MAPPINGS) {
            errors.add("mappings: mapping exceeds " + MAX_MAPPINGS + " entries");
        }

        int index = 0;
        for (JsonNode mapping : mappings) {
            index++;
            validateMapping(mapping, index, allCandidates, sources,
                    backfillIds, logicalClaims, claims, usedCandidates, errors);
        }
        Map<String, Integer> stateClaimsByNode = new HashMap<>();
        for (BackfillClaim claim : claims) {
            if (claim.claimedLevel() != null) {
                stateClaimsByNode.merge(claim.nodeCode(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : stateClaimsByNode.entrySet()) {
            if (entry.getValue() > MAX_STATE_CLAIMS_PER_NODE) {
                errors.add("mappings: node " + entry.getKey() + " exceeds "
                        + MAX_STATE_CLAIMS_PER_NODE + " state-changing claims");
            }
        }
        return new ValidatedBackfill(
                claims, usedCandidates, errors, corpusReport.totalCount());
    }

    private static void validateMapping(
            JsonNode mapping,
            int index,
            Map<String, HistoricalCandidate> candidates,
            Map<String, SourceMetadata> sources,
            Set<String> backfillIds,
            Set<String> logicalClaims,
            List<BackfillClaim> claims,
            Map<String, HistoricalCandidate> usedCandidates,
            List<String> errors) {
        String path = "mapping[" + index + "]";
        int errorCount = errors.size();
        if (mapping == null || !mapping.isObject()) {
            errors.add(path + ": entry must be an object");
            return;
        }

        findProhibitedKeys(mapping, path, errors);
        rejectUnknownFields(mapping, MAPPING_FIELDS, path, errors);

        String backfillId = requiredText(mapping, "backfillId", path, errors);
        if (backfillId != null) {
            if (!BACKFILL_ID.matcher(backfillId).matches()) {
                errors.add(path + ": invalid backfillId " + backfillId);
            } else if (!backfillIds.add(backfillId)) {
                errors.add(path + ": duplicate backfillId " + backfillId);
            }
        }

        String candidateId = requiredText(mapping, "candidateId", path, errors);
        HistoricalCandidate candidate = candidateId == null ? null : candidates.get(candidateId);
        if (candidateId != null && candidate == null) {
            errors.add(path + ": unknown candidate " + candidateId);
        }

        String nodeSetVersion = requiredText(mapping, "nodeSetVersion", path, errors);
        if (nodeSetVersion != null && !NODE_SET_VERSION.equals(nodeSetVersion)) {
            errors.add(path + ": nodeSetVersion must be " + NODE_SET_VERSION);
        }
        String rubricVersion = requiredText(mapping, "rubricVersion", path, errors);
        if (rubricVersion != null && !RUBRIC_VERSION.equals(rubricVersion)) {
            errors.add(path + ": rubricVersion must be " + RUBRIC_VERSION);
        }

        String nodeCode = requiredText(mapping, "nodeCode", path, errors);
        if (nodeCode != null && !NODE_CODES.contains(nodeCode)) {
            errors.add(path + ": unknown nodeCode " + nodeCode);
        }
        String eventType = requiredText(mapping, "eventType", path, errors);
        if (eventType != null && !EVENT_TYPES.contains(eventType)) {
            errors.add(path + ": invalid eventType " + eventType);
        }
        Integer claimedLevel = nullableLevel(mapping.get("claimedLevel"), path, errors);
        if (eventType != null && NON_PROGRESS_EVENTS.contains(eventType) && claimedLevel != null) {
            errors.add(path + ": " + eventType + " requires null claimedLevel");
        } else if (eventType != null && EVENT_TYPES.contains(eventType)
                && !NON_PROGRESS_EVENTS.contains(eventType) && claimedLevel == null) {
            errors.add(path + ": " + eventType + " requires claimedLevel");
        }

        String actor = boundedText(mapping, "actor", 200, path, errors);
        LocalDate occurredOn = date(mapping, "occurredOn", path, errors);
        String precision = requiredText(mapping, "occurredOnPrecision", path, errors);
        if (precision != null && !DATE_PRECISIONS.contains(precision)) {
            errors.add(path + ": invalid occurredOnPrecision " + precision);
        }
        String expectedVerification = requiredText(
                mapping, "expectedVerificationLevel", path, errors);
        VerificationLevel expectedLevel = verificationLevel(expectedVerification, path, errors);
        String eventTitle = boundedText(mapping, "eventTitle", 200, path, errors);
        String rubricJustification = boundedText(
                mapping, "rubricJustification", 1000, path, errors);
        ProgramEndEffect programEndEffect = programEndEffect(mapping, path, errors);
        String programEndScope = optionalBoundedText(
                mapping, "programEndScope", 500, path, errors);
        List<String> evidenceRefs = evidenceRefs(mapping, path, errors);
        BackfillReview review = review(mapping.get("review"), path, errors);

        if (candidate != null) {
            if (eventTitle != null && !eventTitle.equals(candidate.eventTitle())) {
                errors.add(path + ": eventTitle does not match candidate");
            }
            if (actor != null && !actor.equals(candidate.actor())) {
                errors.add(path + ": actor does not match candidate");
            }
            if (occurredOn != null && !occurredOn.equals(candidate.occurredOn())) {
                errors.add(path + ": occurredOn does not match candidate");
            }
            if (precision != null && !precision.equals(candidate.occurredOnPrecision())) {
                errors.add(path + ": occurredOnPrecision does not match candidate");
            }

            List<SourceEvidence> sourceEvidence = resolveEvidence(
                    candidateId, candidate, evidenceRefs, sources, path, errors);
            if (!sourceEvidence.isEmpty() && expectedLevel != null) {
                VerificationLevel derived = VerificationDeriver.derive(sourceEvidence);
                if (derived != expectedLevel) {
                    errors.add(path + ": expectedVerificationLevel mismatch: expected "
                            + expectedLevel + " but derived " + derived);
                }
                if ("ROLLBACK".equals(eventType)
                        && !derived.atLeast(VerificationLevel.OFFICIAL)) {
                    errors.add(path + ": ROLLBACK requires OFFICIAL-or-higher evidence");
                }
                if (programEndEffect == ProgramEndEffect.CAPABILITY_PROGRAM_END
                        && !derived.atLeast(VerificationLevel.OFFICIAL)) {
                    errors.add(path
                            + ": CAPABILITY_PROGRAM_END requires OFFICIAL-or-higher evidence");
                }
            }
        }
        if ("ROLLBACK".equals(eventType) && nodeCode != null && !nodeCode.startsWith("P6-")) {
            errors.add(path + ": ROLLBACK is limited to Pillar 6 nodes");
        }
        if (programEndEffect == ProgramEndEffect.CAPABILITY_PROGRAM_END) {
            if (!"PROGRAM_CANCELLATION".equals(eventType)) {
                errors.add(path
                        + ": CAPABILITY_PROGRAM_END requires PROGRAM_CANCELLATION");
            }
            if (programEndScope == null) {
                errors.add(path + ": CAPABILITY_PROGRAM_END requires programEndScope");
            }
        } else if (programEndScope != null) {
            errors.add(path + ": programEndScope requires CAPABILITY_PROGRAM_END");
        }

        if (errors.size() == errorCount) {
            String logicalKey = String.join("\u001f",
                    candidateId, nodeCode, eventType, occurredOn.toString(),
                    claimedLevel == null ? "" : claimedLevel.toString());
            if (!logicalClaims.add(logicalKey)) {
                errors.add(path + ": duplicate logical claim");
            }
        }

        if (errors.size() == errorCount) {
            BackfillClaim claim = new BackfillClaim(
                    backfillId, candidateId, nodeSetVersion, rubricVersion, nodeCode,
                    eventType, claimedLevel, actor, occurredOn, precision,
                    expectedVerification, eventTitle, rubricJustification,
                    programEndEffect, programEndScope, evidenceRefs, review);
            claims.add(claim);
            usedCandidates.putIfAbsent(candidateId, candidate);
        }
    }

    private static List<SourceEvidence> resolveEvidence(
            String candidateId,
            HistoricalCandidate candidate,
            List<String> refs,
            Map<String, SourceMetadata> sources,
            String path,
            List<String> errors) {
        List<SourceEvidence> resolved = new ArrayList<>();
        Set<String> seenRefs = new HashSet<>();
        for (String ref : refs) {
            if (!seenRefs.add(ref)) {
                errors.add(path + ": duplicate evidence ref " + ref);
                continue;
            }
            String[] parts = ref.split("#", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                errors.add(path + ": invalid evidence ref " + ref);
                continue;
            }
            if (!parts[0].equals(candidateId)) {
                errors.add(path + ": evidence ref candidate mismatch " + ref);
                continue;
            }
            HistoricalEvidenceReference evidence = candidate.evidence().stream()
                    .filter(item -> item.sourceCode().equals(parts[1]))
                    .findFirst()
                    .orElse(null);
            if (evidence == null) {
                errors.add(path + ": unknown evidence ref " + ref);
                continue;
            }
            SourceMetadata source = sources.get(parts[1]);
            if (source == null) {
                errors.add(path + ": sourceCode absent from catalog " + parts[1]);
                continue;
            }
            resolved.add(new SourceEvidence(
                    source.id(), source.tier(), source.sourceType(), evidence.publicationPath()));
        }
        return resolved;
    }

    private static List<String> evidenceRefs(JsonNode mapping, String path, List<String> errors) {
        JsonNode refs = mapping.get("evidenceRefs");
        if (refs == null || !refs.isArray() || refs.isEmpty()) {
            errors.add(path + ": evidenceRefs must not be empty");
            return List.of();
        }
        if (refs.size() > 8) {
            errors.add(path + ": evidenceRefs exceeds 8 entries");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode ref : refs) {
            if (!ref.isTextual() || ref.asText().isBlank()) {
                errors.add(path + ": evidenceRefs entries must be nonblank strings");
            } else {
                result.add(ref.asText().trim());
            }
        }
        return result;
    }

    private static BackfillReview review(JsonNode review, String path, List<String> errors) {
        if (review == null || !review.isObject()) {
            errors.add(path + ": review must be an object");
            return null;
        }
        rejectUnknownFields(review, REVIEW_FIELDS, path + ".review", errors);
        String fact = requiredText(review, "fact", path + ".review", errors);
        String rubric = requiredText(review, "rubric", path + ".review", errors);
        String note = boundedText(review, "reviewerNote", 2000, path + ".review", errors);
        if (!"APPROVED".equals(fact) || !"APPROVED".equals(rubric)) {
            errors.add(path + ": fact and rubric reviews must both be APPROVED");
        }
        return fact == null || rubric == null || note == null
                ? null : new BackfillReview(fact, rubric, note);
    }

    private Map<String, SourceMetadata> readSourceCatalog(List<String> errors) {
        Map<String, SourceMetadata> result = new LinkedHashMap<>();
        JsonNode catalog = readJson(sourceCatalog, "source catalog", errors);
        if (catalog == null || !catalog.isArray()) {
            if (catalog != null) {
                errors.add("source catalog: root must be a JSON array");
            }
            return result;
        }
        long id = 1;
        for (JsonNode source : catalog) {
            String code = text(source, "sourceCode");
            String sourceType = text(source, "sourceType");
            JsonNode tierNode = source.get("tier");
            if (code == null || sourceType == null || tierNode == null || !tierNode.canConvertToInt()) {
                errors.add("source catalog: sourceCode, sourceType, and integer tier are required");
                continue;
            }
            if (result.putIfAbsent(code,
                    new SourceMetadata(id++, tierNode.intValue(), sourceType)) != null) {
                errors.add("source catalog: duplicate sourceCode " + code);
            }
        }
        return result;
    }

    private static Map<String, HistoricalCandidate> readCandidates(
            Resource resource, List<String> errors) {
        Map<String, HistoricalCandidate> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = JSON.readTree(line);
                    List<String> topics = new ArrayList<>();
                    node.path("candidateTopics").forEach(item -> topics.add(item.asText()));
                    List<HistoricalEvidenceReference> evidence = new ArrayList<>();
                    node.path("evidence").forEach(item -> evidence.add(
                            new HistoricalEvidenceReference(
                                    item.path("sourceCode").asText(),
                                    URI.create(item.path("url").asText()),
                                    item.path("locator").asText(),
                                    LocalDate.parse(item.path("accessedOn").asText()),
                                    item.path("contentSha256").asText(),
                                    item.path("publicationPath").asText(),
                                    item.path("factSummary").asText())));
                    HistoricalCandidate candidate = new HistoricalCandidate(
                            node.path("candidateId").asText(),
                            node.path("eventTitle").asText(),
                            topics,
                            node.path("actor").asText(),
                            LocalDate.parse(node.path("occurredOn").asText()),
                            node.path("occurredOnPrecision").asText(),
                            evidence,
                            node.path("discoveryStatus").asText(),
                            node.path("discoveryNote").asText());
                    result.putIfAbsent(candidate.candidateId(), candidate);
                } catch (RuntimeException | IOException e) {
                    errors.add("candidates: line " + lineNumber + ": cannot materialize candidate");
                }
            }
        } catch (IOException e) {
            errors.add("candidates: cannot read resource");
        }
        return result;
    }

    private static JsonNode readJson(Resource resource, String label, List<String> errors) {
        try (var input = resource.getInputStream()) {
            return JSON.readTree(input);
        } catch (IOException e) {
            errors.add(label + ": cannot read JSON resource");
            return null;
        }
    }

    private static VerificationLevel verificationLevel(
            String value, String path, List<String> errors) {
        if (value == null) {
            return null;
        }
        try {
            return VerificationLevel.valueOf(value);
        } catch (IllegalArgumentException e) {
            errors.add(path + ": invalid expectedVerificationLevel " + value);
            return null;
        }
    }

    private static Integer nullableLevel(JsonNode node, String path, List<String> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < 1 || node.intValue() > 9) {
            errors.add(path + ": claimedLevel must be null or between 1 and 9");
            return null;
        }
        return node.intValue();
    }

    private static LocalDate date(
            JsonNode object, String field, String path, List<String> errors) {
        String value = requiredText(object, field, path, errors);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            errors.add(path + ": invalid " + field);
            return null;
        }
    }

    private static String boundedText(
            JsonNode object, String field, int maxLength, String path, List<String> errors) {
        String value = requiredText(object, field, path, errors);
        if (value != null && value.length() > maxLength) {
            errors.add(path + ": " + field + " exceeds " + maxLength + " characters");
            return null;
        }
        return value;
    }

    private static String optionalBoundedText(
            JsonNode object, String field, int maxLength, String path, List<String> errors) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            errors.add(path + ": " + field + " must be a nonblank string");
            return null;
        }
        String value = node.asText().trim();
        if (value.length() > maxLength) {
            errors.add(path + ": " + field + " exceeds " + maxLength + " characters");
            return null;
        }
        return value;
    }

    private static ProgramEndEffect programEndEffect(
            JsonNode mapping, String path, List<String> errors) {
        String value = text(mapping, "programEndEffect");
        if (value == null) {
            return ProgramEndEffect.NONE;
        }
        try {
            return ProgramEndEffect.valueOf(value);
        } catch (IllegalArgumentException e) {
            errors.add(path + ": invalid programEndEffect " + value);
            return ProgramEndEffect.NONE;
        }
    }

    private static boolean withinSize(
            Resource resource, long maxBytes, String label, List<String> errors) {
        try {
            long bytes = resource.contentLength();
            if (bytes > maxBytes) {
                errors.add(label + " exceeds " + maxBytes + " UTF-8 bytes");
                return false;
            }
            return true;
        } catch (IOException e) {
            errors.add(label + " size cannot be read");
            return false;
        }
    }

    private static String requiredText(
            JsonNode object, String field, String path, List<String> errors) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            errors.add(path + ": " + field + " must not be blank");
            return null;
        }
        return value.asText().trim();
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank()
                ? null : value.asText().trim();
    }

    private static void rejectUnknownFields(
            JsonNode object, Set<String> allowed, String path, List<String> errors) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                errors.add(path + ": unknown field " + name);
            }
        }
    }

    private static void findProhibitedKeys(JsonNode node, String path, List<String> errors) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (PROHIBITED_KEYS.contains(normalizedKey(name))) {
                    errors.add(path + ": prohibited field " + name);
                }
                findProhibitedKeys(node.get(name), path + "." + name, errors);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                findProhibitedKeys(child, path + "[" + index++ + "]", errors);
            }
        }
    }

    private static String normalizedKey(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private record SourceMetadata(long id, int tier, String sourceType) {
    }
}
