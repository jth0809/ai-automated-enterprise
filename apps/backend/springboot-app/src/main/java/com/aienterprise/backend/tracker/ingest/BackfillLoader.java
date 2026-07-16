package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.backfill.BackfillClaim;
import com.aienterprise.backend.tracker.backfill.BackfillDatasetValidator;
import com.aienterprise.backend.tracker.backfill.HistoricalCandidate;
import com.aienterprise.backend.tracker.backfill.HistoricalEvidenceReference;
import com.aienterprise.backend.tracker.backfill.ProgramEndEffect;
import com.aienterprise.backend.tracker.backfill.ValidatedBackfill;
import com.aienterprise.backend.tracker.backfill.WeeklyBackfillProjector;
import com.aienterprise.backend.tracker.domain.BackfillImportRow;
import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.HistoricalEvidenceRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.event.EventMerger;
import com.aienterprise.backend.tracker.event.SourceEvidence;
import com.aienterprise.backend.tracker.event.VerificationDeriver;
import com.aienterprise.backend.tracker.event.VerificationLevel;
import com.aienterprise.backend.tracker.math.Params;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class BackfillLoader {

    private static final Logger log = LoggerFactory.getLogger(BackfillLoader.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE_SET_VERSION = "nodes-v1.0";
    private static final String RUBRIC_VERSION = "r2.0";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> NON_STATE_EVENTS = Set.of(
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY", "RETROSPECTIVE");

    private final TrackerRepository repository;
    private final Resource candidatesResource;
    private final Resource mappingsResource;
    private final String datasetVersion;
    private final WeeklyBackfillProjector weeklyProjector;

    @Autowired
    public BackfillLoader(
            TrackerRepository repository,
            @Value("${tracker.backfill-resource:tracker/backfill-v1.json}") String mappingsPath,
            @Value("${tracker.backfill-candidates-resource:tracker/historical-candidates-v1.jsonl}")
            String candidatesPath,
            @Value("${tracker.backfill-dataset-version:backfill-v1}") String datasetVersion,
            WeeklyBackfillProjector weeklyProjector) {
        this(repository,
                new ClassPathResource(candidatesPath),
                new ClassPathResource(mappingsPath),
                datasetVersion,
                weeklyProjector);
    }

    BackfillLoader(
            TrackerRepository repository,
            Resource candidatesResource,
            Resource mappingsResource,
            String datasetVersion,
            WeeklyBackfillProjector weeklyProjector) {
        this.repository = repository;
        this.candidatesResource = candidatesResource;
        this.mappingsResource = mappingsResource;
        this.datasetVersion = requireDatasetVersion(datasetVersion);
        this.weeklyProjector = weeklyProjector;
    }

    @Transactional
    @SchedulerLock(name = "tracker-backfill-import", lockAtMostFor = "PT30M")
    public void loadDatasetIfNeeded() {
        DatasetContent content = readContent();
        String datasetSha256 = datasetHash(content);
        var existing = repository.findBackfillImport(datasetVersion);
        if (existing.isPresent()) {
            if (!datasetSha256.equals(existing.get().datasetSha256())) {
                throw new IllegalStateException(
                        "Tracker backfill dataset hash mismatch for " + datasetVersion);
            }
            if (weeklyProjector.isCurrent(
                    datasetSha256, NODE_SET_VERSION, RUBRIC_VERSION)) {
                return;
            }
        }

        ValidatedBackfill validated = new BackfillDatasetValidator(
                datasetVersion.startsWith("backfill-v"))
                .validate(candidatesResource, mappingsResource);
        if (!validated.errors().isEmpty()) {
            throw new IllegalStateException("Invalid tracker backfill dataset " + datasetVersion
                    + System.lineSeparator()
                    + String.join(System.lineSeparator(), validated.errors()));
        }
        if (validated.claims().isEmpty()) {
            log.info("tracker backfill dataset {} is empty; skipping import", datasetVersion);
            return;
        }

        List<BackfillClaim> claims = new ArrayList<>(validated.claims());
        claims.sort(Comparator.comparing(BackfillClaim::occurredOn)
                .thenComparing(BackfillClaim::backfillId));
        if (existing.isEmpty()) {
            long rubricVersionId = repository.rubricVersionIdByLabel(RUBRIC_VERSION);
            Set<Long> preservedLiveNodeIds = repository.findNodeIdsWithConfirmedState();
            for (BackfillClaim claim : claims) {
                importClaim(claim, validated.candidates().get(claim.candidateId()),
                        rubricVersionId, preservedLiveNodeIds);
            }
            applyCurrentDormancy();
            weeklyProjector.project(
                    claims, datasetSha256, NODE_SET_VERSION, RUBRIC_VERSION);
            repository.recordBackfillImport(BackfillImportRow.draft(
                    datasetVersion, datasetSha256, NODE_SET_VERSION,
                    rubricVersionId, claims.size()));
            log.info("tracker backfill imported {} reviewed claims from dataset {}",
                    claims.size(), datasetVersion);
            return;
        }

        WeeklyBackfillProjector.ProjectionSummary summary = weeklyProjector.project(
                claims, datasetSha256, NODE_SET_VERSION, RUBRIC_VERSION);
        log.info("tracker weekly backfill projection repaired through {} with {} inserted rows",
                summary.throughMonday(), summary.insertedRows());
    }

    /**
     * Compatibility entry point for callers written before dataset-hash
     * idempotency. It no longer checks whether the event table is empty.
     */
    @Transactional
    @SchedulerLock(name = "tracker-backfill-import", lockAtMostFor = "PT30M")
    public void loadIfEmpty() {
        loadDatasetIfNeeded();
    }

    private void importClaim(
            BackfillClaim claim,
            HistoricalCandidate candidate,
            long rubricVersionId,
            Set<Long> preservedLiveNodeIds) {
        if (candidate == null) {
            throw new IllegalStateException("Validated candidate disappeared: " + claim.candidateId());
        }
        NodeRow node = repository.findNodeByCode(claim.nodeCode());
        List<EvidenceBinding> bindings = resolveEvidence(claim, candidate);
        VerificationLevel derived = VerificationDeriver.derive(
                bindings.stream().map(EvidenceBinding::sourceEvidence).toList());
        if (!derived.name().equals(claim.expectedVerificationLevel())) {
            throw new IllegalStateException("Verification changed during import for "
                    + claim.backfillId() + ": expected " + claim.expectedVerificationLevel()
                    + " but derived " + derived);
        }

        long eventId = repository.upsertEventByNaturalKey(
                EventMerger.naturalKey(
                        claim.nodeCode(), claim.eventType(), claim.actor(), claim.occurredOn()),
                EventRow.draft(
                        node.id(), claim.eventType(), claim.claimedLevel(), claim.actor(),
                        claim.occurredOn(), derived.name(), null, rubricVersionId));
        EventRow event = repository.findEventById(eventId);
        VerificationLevel mergedVerification = max(
                VerificationLevel.valueOf(event.verificationLevel()), derived);
        if (!mergedVerification.name().equals(event.verificationLevel())) {
            repository.updateEventVerification(eventId, mergedVerification.name());
        }
        for (EvidenceBinding binding : bindings) {
            HistoricalEvidenceReference reference = binding.reference();
            validatePersistedReference(reference);
            repository.insertHistoricalEvidence(HistoricalEvidenceRow.draft(
                    claim.backfillId(),
                    claim.candidateId(),
                    claim.occurredOnPrecision(),
                    eventId,
                    binding.sourceEvidence().sourceId(),
                    reference.url().toString(),
                    reference.locator(),
                    reference.accessedOn(),
                    reference.contentSha256(),
                    reference.publicationPath(),
                    reference.factSummary(),
                    claim.review().reviewerNote()));
        }
        boolean stateChanged = applyStateTransition(
                claim, node, eventId, mergedVerification.name(), rubricVersionId,
                preservedLiveNodeIds.contains(node.id()));
        repository.markEventConfirmed(eventId, event.stateAdvanced() || stateChanged);
    }

    private List<EvidenceBinding> resolveEvidence(
            BackfillClaim claim, HistoricalCandidate candidate) {
        List<EvidenceBinding> result = new ArrayList<>();
        for (String evidenceRef : claim.evidenceRefs()) {
            String sourceCode = evidenceRef.substring(evidenceRef.indexOf('#') + 1);
            HistoricalEvidenceReference reference = candidate.evidence().stream()
                    .filter(item -> item.sourceCode().equals(sourceCode))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Validated evidence disappeared: " + evidenceRef));
            SourceEvidence sourceEvidence = repository.sourceEvidenceByCode(
                    sourceCode, reference.publicationPath());
            result.add(new EvidenceBinding(reference, sourceEvidence));
        }
        return result;
    }

    private boolean applyStateTransition(
            BackfillClaim claim,
            NodeRow node,
            long eventId,
            String verification,
            long rubricVersionId,
            boolean preserveLiveState) {
        if (preserveLiveState) {
            return false;
        }
        if ("PROGRAM_CANCELLATION".equals(claim.eventType())) {
            if (claim.programEndEffect() == ProgramEndEffect.CAPABILITY_PROGRAM_END) {
                repository.recordProgramEndDate(node.id(), claim.occurredOn());
            }
            return false;
        }
        if (claim.claimedLevel() == null || NON_STATE_EVENTS.contains(claim.eventType())) {
            return false;
        }
        if ("ROLLBACK".equals(claim.eventType())) {
            if (!VerificationLevel.valueOf(verification).atLeast(VerificationLevel.OFFICIAL)) {
                throw new IllegalStateException(
                        "Historical rollback requires OFFICIAL evidence: " + claim.backfillId());
            }
            if (claim.claimedLevel() >= node.currentLevel()) {
                throw new IllegalStateException(
                        "Historical rollback must lower current level: " + claim.backfillId());
            }
            repository.advanceNode(
                    node.id(), claim.claimedLevel(), verification, eventId, rubricVersionId);
            return true;
        }
        if (claim.claimedLevel() > node.currentLevel()
                || node.programEndDate() != null
                || "DORMANT".equals(node.nodeStatus())) {
            repository.advanceNode(
                    node.id(), Math.max(node.currentLevel(), claim.claimedLevel()),
                    verification, eventId, rubricVersionId);
            return true;
        }
        return false;
    }

    private void applyCurrentDormancy() {
        Params params = Params.defaults();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (NodeRow node : repository.findAllNodes()) {
            if (node.programEndDate() == null) {
                continue;
            }
            LocalDate dormantSince = node.programEndDate().plusYears(params.dormancyTriggerYears());
            if (!today.isBefore(dormantSince)) {
                repository.markNodeDormant(node.id(), dormantSince);
            }
        }
    }

    private DatasetContent readContent() {
        try {
            return new DatasetContent(
                    candidatesResource.getContentAsByteArray(),
                    mappingsResource.getContentAsByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot load tracker backfill dataset " + datasetVersion, e);
        }
    }

    private String datasetHash(DatasetContent content) {
        try {
            String candidates = normalizeLf(
                    new String(content.candidates(), StandardCharsets.UTF_8));
            JsonNode mappings = JSON.readTree(content.mappings());
            byte[] canonicalMappings = JSON.writeValueAsBytes(canonicalize(mappings));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("datasetVersion=" + datasetVersion + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(("nodeSetVersion=" + NODE_SET_VERSION + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(("rubricVersion=" + RUBRIC_VERSION + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            digest.update("candidates\n".getBytes(StandardCharsets.UTF_8));
            digest.update(candidates.getBytes(StandardCharsets.UTF_8));
            digest.update("\nmappings\n".getBytes(StandardCharsets.UTF_8));
            digest.update(canonicalMappings);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot canonicalize tracker backfill mappings " + datasetVersion, e);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                result.set(name, canonicalize(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            for (JsonNode item : node) {
                result.add(canonicalize(item));
            }
            return result;
        }
        return node.deepCopy();
    }

    private static void validatePersistedReference(HistoricalEvidenceReference reference) {
        URI url = reference.url();
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
            throw new IllegalArgumentException("Historical evidence URL must be HTTPS");
        }
        if (!SHA256.matcher(reference.contentSha256()).matches()) {
            throw new IllegalArgumentException(
                    "Historical evidence SHA-256 must be lowercase hexadecimal");
        }
    }

    private static String normalizeLf(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static VerificationLevel max(
            VerificationLevel left, VerificationLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static String requireDatasetVersion(String value) {
        if (value == null || value.isBlank() || value.length() > 40) {
            throw new IllegalArgumentException(
                    "tracker.backfill-dataset-version must be 1-40 characters");
        }
        return value.trim();
    }

    private record DatasetContent(byte[] candidates, byte[] mappings) {
    }

    private record EvidenceBinding(
            HistoricalEvidenceReference reference,
            SourceEvidence sourceEvidence) {
    }

}
