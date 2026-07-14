package com.aienterprise.backend.tracker.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.Resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class BackfillAuditValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_AUDIT_BYTES = 256L * 1024;
    private static final int MAX_TEXT = 1000;
    private static final int DORMANCY_TRIGGER_YEARS = 15;
    private static final String NODE_SET_VERSION = "nodes-v1.0";
    private static final String RUBRIC_VERSION = "r2.0";
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DORMANT");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "nodeCode", "nodeSetVersion", "rubricVersion", "auditedOn",
            "auditedLevel", "status", "levelClaimIds", "levelEvidenceRefs",
            "statusClaimIds", "nextLevelGap", "statusRationale", "reviews");
    private static final Set<String> REVIEW_FIELDS = Set.of(
            "reviewerId", "focus", "decision");
    private static final Set<String> REVIEW_FOCUSES = Set.of("FACT", "RUBRIC");
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

    private final Clock clock;
    private final Set<String> expectedNodeCodes;

    public BackfillAuditValidator(Clock clock) {
        this(clock, NODE_CODES);
    }

    BackfillAuditValidator(Clock clock, Set<String> expectedNodeCodes) {
        this.clock = clock;
        this.expectedNodeCodes = Set.copyOf(expectedNodeCodes);
    }

    public ValidatedNodeAudit validate(Resource auditResource, ValidatedBackfill backfill) {
        List<String> errors = new ArrayList<>();
        if (!backfill.errors().isEmpty()) {
            errors.add("backfill: audit requires an error-free validated backfill");
            return new ValidatedNodeAudit(List.of(), errors);
        }
        byte[] bytes = readBounded(auditResource, errors);
        if (bytes == null) {
            return new ValidatedNodeAudit(List.of(), errors);
        }
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException e) {
            errors.add("audit: invalid JSON");
            return new ValidatedNodeAudit(List.of(), errors);
        }
        if (root == null || !root.isArray()) {
            errors.add("audit: root must be a JSON array");
            return new ValidatedNodeAudit(List.of(), errors);
        }
        if (root.size() > NODE_CODES.size()) {
            errors.add("audit: exceeds " + NODE_CODES.size() + " entries");
        }

        Map<String, BackfillClaim> claimsById = new HashMap<>();
        for (BackfillClaim claim : backfill.claims()) {
            claimsById.put(claim.backfillId(), claim);
        }
        Map<String, ReplayState> replay = replay(backfill.claims());
        List<NodeAuditEntry> entries = new ArrayList<>();
        Set<String> seenNodes = new HashSet<>();
        int index = 0;
        for (JsonNode node : root) {
            index++;
            NodeAuditEntry entry = validateEntry(
                    node, index, claimsById, replay, seenNodes, errors);
            if (entry != null) {
                entries.add(entry);
            }
        }
        for (String expected : expectedNodeCodes) {
            if (!seenNodes.contains(expected)) {
                errors.add("audit: missing nodeCode " + expected);
            }
        }
        return new ValidatedNodeAudit(entries, errors);
    }

    private NodeAuditEntry validateEntry(
            JsonNode node,
            int index,
            Map<String, BackfillClaim> claimsById,
            Map<String, ReplayState> replay,
            Set<String> seenNodes,
            List<String> errors) {
        String path = "audit[" + index + "]";
        int errorCount = errors.size();
        if (node == null || !node.isObject()) {
            errors.add(path + ": entry must be an object");
            return null;
        }
        rejectUnknownFields(node, ENTRY_FIELDS, path, errors);
        String nodeCode = requiredText(node, "nodeCode", 80, path, errors);
        if (nodeCode != null) {
            if (!expectedNodeCodes.contains(nodeCode)) {
                errors.add(path + ": unknown nodeCode " + nodeCode);
            } else if (!seenNodes.add(nodeCode)) {
                errors.add(path + ": duplicate nodeCode " + nodeCode);
            }
        }
        String nodeSetVersion = requiredText(
                node, "nodeSetVersion", 40, path, errors);
        if (nodeSetVersion != null && !NODE_SET_VERSION.equals(nodeSetVersion)) {
            errors.add(path + ": nodeSetVersion must be " + NODE_SET_VERSION);
        }
        String rubricVersion = requiredText(
                node, "rubricVersion", 40, path, errors);
        if (rubricVersion != null && !RUBRIC_VERSION.equals(rubricVersion)) {
            errors.add(path + ": rubricVersion must be " + RUBRIC_VERSION);
        }
        LocalDate auditedOn = date(node, "auditedOn", path, errors);
        LocalDate asOf = LocalDate.now(clock);
        if (auditedOn != null && auditedOn.isAfter(asOf)) {
            errors.add(path + ": auditedOn cannot be in the future");
        }
        Integer auditedLevel = level(node.get("auditedLevel"), path, errors);
        String status = requiredText(node, "status", 20, path, errors);
        if (status != null && !STATUSES.contains(status)) {
            errors.add(path + ": status must be ACTIVE or DORMANT");
        }
        List<String> levelClaimIds = stringArray(
                node, "levelClaimIds", 8, path, errors);
        List<String> levelEvidenceRefs = stringArray(
                node, "levelEvidenceRefs", 16, path, errors);
        List<String> statusClaimIds = stringArray(
                node, "statusClaimIds", 8, path, errors);
        String nextLevelGap = requiredText(
                node, "nextLevelGap", MAX_TEXT, path, errors);
        String statusRationale = requiredText(
                node, "statusRationale", MAX_TEXT, path, errors);
        List<NodeAuditApproval> reviews = reviews(node.get("reviews"), path, errors);

        if (auditedLevel != null && nodeCode != null) {
            validateLevelEvidence(
                    nodeCode, auditedLevel, levelClaimIds, levelEvidenceRefs,
                    claimsById, path, errors);
            validateStatusEvidence(
                    nodeCode, status, statusClaimIds, claimsById, path, errors);
            ReplayState expected = replay.getOrDefault(nodeCode, ReplayState.initial());
            LocalDate replayDate = auditedOn == null ? asOf : auditedOn;
            String expectedStatus = expected.isDormant(replayDate) ? "DORMANT" : "ACTIVE";
            if (auditedLevel != expected.level()) {
                errors.add(path + ": auditedLevel " + auditedLevel
                        + " does not match replay level " + expected.level());
            }
            if (status != null && !status.equals(expectedStatus)) {
                errors.add(path + ": status " + status
                        + " does not match replay status " + expectedStatus);
            }
        }

        if (errors.size() != errorCount) {
            return null;
        }
        return new NodeAuditEntry(
                nodeCode, nodeSetVersion, rubricVersion, auditedOn, auditedLevel,
                status, levelClaimIds, levelEvidenceRefs, statusClaimIds,
                nextLevelGap, statusRationale, reviews);
    }

    private static void validateLevelEvidence(
            String nodeCode,
            int auditedLevel,
            List<String> claimIds,
            List<String> evidenceRefs,
            Map<String, BackfillClaim> claimsById,
            String path,
            List<String> errors) {
        if (auditedLevel == 0) {
            if (!claimIds.isEmpty() || !evidenceRefs.isEmpty()) {
                errors.add(path
                        + ": level 0 requires empty levelClaimIds and levelEvidenceRefs");
            }
            return;
        }
        if (claimIds.isEmpty() || evidenceRefs.isEmpty()) {
            errors.add(path + ": level above 0 requires direct claim and evidence refs");
            return;
        }
        Set<String> expectedRefs = new HashSet<>();
        for (String claimId : claimIds) {
            BackfillClaim claim = claimsById.get(claimId);
            if (claim == null) {
                errors.add(path + ": unknown level claim " + claimId);
                continue;
            }
            if (!nodeCode.equals(claim.nodeCode())) {
                errors.add(path + ": level claim " + claimId + " belongs to "
                        + claim.nodeCode());
            }
            if (claim.claimedLevel() == null || claim.claimedLevel() != auditedLevel) {
                errors.add(path + ": level claim " + claimId
                        + " does not support audited level " + auditedLevel);
            }
            expectedRefs.addAll(claim.evidenceRefs());
        }
        if (!expectedRefs.equals(new HashSet<>(evidenceRefs))) {
            errors.add(path + ": levelEvidenceRefs must exactly match level claim evidence");
        }
    }

    private static void validateStatusEvidence(
            String nodeCode,
            String status,
            List<String> claimIds,
            Map<String, BackfillClaim> claimsById,
            String path,
            List<String> errors) {
        boolean hasCapabilityEnd = false;
        for (String claimId : claimIds) {
            BackfillClaim claim = claimsById.get(claimId);
            if (claim == null) {
                errors.add(path + ": unknown status claim " + claimId);
                continue;
            }
            if (!nodeCode.equals(claim.nodeCode())) {
                errors.add(path + ": status claim " + claimId + " belongs to "
                        + claim.nodeCode());
            }
            if (claim.claimedLevel() != null) {
                errors.add(path + ": status claim " + claimId
                        + " must be a non-state claim");
            }
            hasCapabilityEnd |= claim.programEndEffect()
                    == ProgramEndEffect.CAPABILITY_PROGRAM_END;
        }
        if ("DORMANT".equals(status) && !hasCapabilityEnd) {
            errors.add(path + ": DORMANT requires a CAPABILITY_PROGRAM_END status claim");
        }
    }

    private Map<String, ReplayState> replay(List<BackfillClaim> claims) {
        List<BackfillClaim> ordered = new ArrayList<>(claims);
        ordered.sort(Comparator.comparing(BackfillClaim::occurredOn)
                .thenComparing(BackfillClaim::backfillId));
        Map<String, ReplayState> states = new LinkedHashMap<>();
        for (String nodeCode : expectedNodeCodes) {
            states.put(nodeCode, ReplayState.initial());
        }
        for (BackfillClaim claim : ordered) {
            if (!expectedNodeCodes.contains(claim.nodeCode())) {
                continue;
            }
            ReplayState previous = states.get(claim.nodeCode());
            if ("PROGRAM_CANCELLATION".equals(claim.eventType())) {
                if (claim.programEndEffect() == ProgramEndEffect.CAPABILITY_PROGRAM_END) {
                    states.put(claim.nodeCode(), new ReplayState(
                            previous.level(), claim.occurredOn()));
                }
                continue;
            }
            if (claim.claimedLevel() == null) {
                continue;
            }
            if ("ROLLBACK".equals(claim.eventType())) {
                if (claim.claimedLevel() < previous.level()) {
                    states.put(claim.nodeCode(), new ReplayState(
                            claim.claimedLevel(), previous.programEndDate()));
                }
                continue;
            }
            if (claim.claimedLevel() > previous.level()
                    || previous.programEndDate() != null) {
                states.put(claim.nodeCode(), new ReplayState(
                        Math.max(previous.level(), claim.claimedLevel()), null));
            }
        }
        return states;
    }

    private static List<NodeAuditApproval> reviews(
            JsonNode node, String path, List<String> errors) {
        if (node == null || !node.isArray() || node.size() != 2) {
            errors.add(path + ": reviews must contain exactly two approvals");
            return List.of();
        }
        List<NodeAuditApproval> result = new ArrayList<>();
        Set<String> reviewers = new HashSet<>();
        Set<String> focuses = new HashSet<>();
        int index = 0;
        for (JsonNode review : node) {
            index++;
            String reviewPath = path + ".reviews[" + index + "]";
            if (!review.isObject()) {
                errors.add(reviewPath + ": review must be an object");
                continue;
            }
            rejectUnknownFields(review, REVIEW_FIELDS, reviewPath, errors);
            String reviewerId = requiredText(
                    review, "reviewerId", 100, reviewPath, errors);
            String focus = requiredText(review, "focus", 20, reviewPath, errors);
            String decision = requiredText(
                    review, "decision", 20, reviewPath, errors);
            if (reviewerId != null) {
                reviewers.add(reviewerId);
            }
            if (focus != null) {
                focuses.add(focus);
            }
            if (decision != null && !"APPROVED".equals(decision)) {
                errors.add(reviewPath + ": decision must be APPROVED");
            }
            if (reviewerId != null && focus != null && decision != null) {
                result.add(new NodeAuditApproval(reviewerId, focus, decision));
            }
        }
        if (reviewers.size() != 2) {
            errors.add(path + ": reviews require distinct reviewerId values");
        }
        if (!focuses.equals(REVIEW_FOCUSES)) {
            errors.add(path + ": reviews must cover FACT and RUBRIC exactly once");
        }
        return result;
    }

    private static List<String> stringArray(
            JsonNode object,
            String field,
            int maxEntries,
            String path,
            List<String> errors) {
        JsonNode node = object.get(field);
        if (node == null || !node.isArray()) {
            errors.add(path + ": " + field + " must be an array");
            return List.of();
        }
        if (node.size() > maxEntries) {
            errors.add(path + ": " + field + " exceeds " + maxEntries + " entries");
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                errors.add(path + ": " + field + " entries must be nonblank strings");
                continue;
            }
            String text = value.asText().trim();
            if (!unique.add(text)) {
                errors.add(path + ": duplicate " + field + " value " + text);
            }
            result.add(text);
        }
        return result;
    }

    private static String requiredText(
            JsonNode object,
            String field,
            int maxLength,
            String path,
            List<String> errors) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            errors.add(path + ": " + field + " must be a nonblank string");
            return null;
        }
        String value = node.asText().trim();
        if (value.length() > maxLength) {
            errors.add(path + ": " + field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    private static LocalDate date(
            JsonNode object, String field, String path, List<String> errors) {
        String value = requiredText(object, field, 10, path, errors);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            errors.add(path + ": invalid " + field);
            return null;
        }
    }

    private static Integer level(JsonNode node, String path, List<String> errors) {
        if (node == null || !node.canConvertToInt()) {
            errors.add(path + ": auditedLevel must be an integer between 0 and 9");
            return null;
        }
        int value = node.intValue();
        if (value < 0 || value > 9) {
            errors.add(path + ": auditedLevel must be an integer between 0 and 9");
            return null;
        }
        return value;
    }

    private static void rejectUnknownFields(
            JsonNode object,
            Set<String> allowed,
            String path,
            List<String> errors) {
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                errors.add(path + ": unknown field " + field);
            }
        }
    }

    private static byte[] readBounded(Resource resource, List<String> errors) {
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes((int) MAX_AUDIT_BYTES + 1);
            if (bytes.length > MAX_AUDIT_BYTES) {
                errors.add("audit: resource exceeds " + MAX_AUDIT_BYTES + " bytes");
                return null;
            }
            return bytes;
        } catch (IOException e) {
            errors.add("audit: unable to read resource");
            return null;
        }
    }

    private record ReplayState(int level, LocalDate programEndDate) {

        private static ReplayState initial() {
            return new ReplayState(0, null);
        }

        private boolean isDormant(LocalDate asOf) {
            return programEndDate != null
                    && !asOf.isBefore(programEndDate.plusYears(DORMANCY_TRIGGER_YEARS));
        }
    }
}
