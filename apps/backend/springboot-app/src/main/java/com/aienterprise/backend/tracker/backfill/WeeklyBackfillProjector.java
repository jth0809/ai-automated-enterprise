package com.aienterprise.backend.tracker.backfill;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.CapabilityReadinessService;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.math.LogitEta;
import com.aienterprise.backend.tracker.math.ModelParameterRepository;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.Params;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class WeeklyBackfillProjector {

    static final String MARKER_KEY = "BACKFILL_WEEKLY_PROJECTION_V1";
    static final LocalDate DEFAULT_FIRST_MONDAY = LocalDate.of(1957, 1, 7);
    static final String DEFAULT_PROJECTOR_VERSION = "weekly-projector-v2";

    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> NON_STATE_EVENTS = Set.of(
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY", "RETROSPECTIVE");
    private static final double READINESS_TOLERANCE = 0.0000051;
    private static final double LOGIT_TOLERANCE = 0.00000051;

    private final TrackerRepository repository;
    private final CapabilityReadinessService readinessService;
    private final ModelParameterRepository parameterRepository;
    private final Clock clock;
    private final LocalDate firstMonday;
    private final String projectorVersion;

    @Autowired
    public WeeklyBackfillProjector(
            TrackerRepository repository,
            CapabilityReadinessService readinessService,
            ModelParameterRepository parameterRepository) {
        this(repository, readinessService, parameterRepository, Clock.systemUTC());
    }

    public WeeklyBackfillProjector(
            TrackerRepository repository,
            CapabilityReadinessService readinessService,
            ModelParameterRepository parameterRepository,
            Clock clock) {
        this(repository, readinessService, parameterRepository,
                clock, DEFAULT_FIRST_MONDAY, DEFAULT_PROJECTOR_VERSION);
    }

    WeeklyBackfillProjector(
            TrackerRepository repository,
            CapabilityReadinessService readinessService,
            ModelParameterRepository parameterRepository,
            Clock clock,
            LocalDate firstMonday,
            String projectorVersion) {
        if (firstMonday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("firstMonday must be a Monday");
        }
        if (projectorVersion == null || projectorVersion.isBlank()
                || projectorVersion.length() > 80 || projectorVersion.contains("|")) {
            throw new IllegalArgumentException("invalid projectorVersion");
        }
        this.repository = repository;
        this.readinessService = readinessService;
        this.parameterRepository = parameterRepository;
        this.clock = clock;
        this.firstMonday = firstMonday;
        this.projectorVersion = projectorVersion;
    }

    public boolean isCurrent(
            String datasetSha256, String nodeSetVersion, String rubricVersion) {
        LocalDate target = lastCompletedMonday();
        List<NodeRow> nodes = repository.findAllNodes();
        ModelParameters model = parameterRepository.loadActive();
        CapabilityGraph graph = readinessService.loadActiveGraph(nodes);
        ProjectionMarker marker = readMarker();
        return marker != null
                && marker.fingerprint().equals(fingerprint(
                        datasetSha256, nodeSetVersion, rubricVersion,
                        model.params(), graph))
                && marker.through().equals(target);
    }

    @Transactional
    public ProjectionSummary project(
            List<BackfillClaim> inputClaims,
            String datasetSha256,
            String nodeSetVersion,
            String rubricVersion) {
        List<NodeRow> nodes = repository.findAllNodes();
        ModelParameters model = parameterRepository.loadActive();
        Params params = model.params();
        CapabilityGraph graph = readinessService.loadActiveGraph(nodes);
        String fingerprint = fingerprint(
                datasetSha256, nodeSetVersion, rubricVersion, params, graph);
        LocalDate target = lastCompletedMonday();
        if (target.isBefore(firstMonday)) {
            throw new IllegalStateException("weekly projection target precedes first Monday");
        }

        ProjectionMarker previous = readMarker();
        if (previous != null
                && previous.fingerprint().equals(fingerprint)
                && previous.through().equals(target)) {
            return new ProjectionSummary(firstMonday, target, 0, false);
        }

        boolean rebuild = previous == null
                || !previous.fingerprint().equals(fingerprint)
                || previous.through().isBefore(firstMonday.minusWeeks(1))
                || previous.through().isAfter(target)
                || previous.through().getDayOfWeek() != DayOfWeek.MONDAY;
        LocalDate writeAfter = rebuild ? firstMonday.minusWeeks(1) : previous.through();
        if (rebuild) {
            repository.deleteBareHistoricalPillarSnapshots(firstMonday, MAX_DATE);
        }

        LocalDate writeFrom = writeAfter.plusWeeks(1);
        Map<SnapshotKey, SnapshotRow> existing = new HashMap<>();
        if (!writeFrom.isAfter(target)) {
            for (SnapshotRow row : repository.findPillarSnapshotsBetween(writeFrom, target)) {
                if (row.pillar() >= 1 && row.pillar() <= 6) {
                    existing.put(new SnapshotKey(row.pillar(), row.snapshotDate()), row);
                }
            }
        }

        List<BackfillClaim> claims = new ArrayList<>(inputClaims);
        claims.sort(Comparator.comparing(BackfillClaim::occurredOn)
                .thenComparing(BackfillClaim::backfillId));
        Map<String, ReplayState> states = new LinkedHashMap<>();
        List<SnapshotRow> inserts = new ArrayList<>();
        int nextClaim = 0;

        for (LocalDate date = firstMonday; !date.isAfter(target); date = date.plusWeeks(1)) {
            while (nextClaim < claims.size()
                    && !claims.get(nextClaim).occurredOn().isAfter(date)) {
                replayTransition(states, claims.get(nextClaim++));
            }
            if (!date.isAfter(writeAfter)) {
                continue;
            }
            List<NodeRow> replayNodes = materializeNodes(nodes, states, params, date);
            ReadinessResult readiness = readinessService.calculate(
                    replayNodes, graph, params, date);
            for (int pillar = 1; pillar <= 6; pillar++) {
                double rawPillar = readiness.rawPillarReadiness()
                        .getOrDefault(pillar, 0.0);
                double effectivePillar = readiness.effectivePillarReadiness()
                        .getOrDefault(pillar, 0.0);
                double logit = LogitEta.logitClipped(effectivePillar, params.epsilon());
                SnapshotKey key = new SnapshotKey(pillar, date);
                SnapshotRow existingRow = existing.get(key);
                if (existingRow != null) {
                    assertCompatible(
                            existingRow, rawPillar, effectivePillar, logit,
                            params.version(), graph.version());
                    continue;
                }
                inserts.add(new SnapshotRow(
                        0, pillar, date, effectivePillar, logit,
                        null, null, null, null, null, null, null, null,
                        params.version(), rawPillar, graph.version()));
            }
        }

        repository.insertBareHistoricalPillarSnapshots(inserts);
        repository.putOpsState(MARKER_KEY, fingerprint + "|" + target);
        return new ProjectionSummary(firstMonday, target, inserts.size(), rebuild);
    }

    LocalDate lastCompletedMonday() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return today.equals(monday) ? monday.minusWeeks(1) : monday;
    }

    static Map<String, ProjectionNodeState> replayStates(
            List<BackfillClaim> inputClaims, LocalDate asOf) {
        List<BackfillClaim> claims = new ArrayList<>(inputClaims);
        claims.sort(Comparator.comparing(BackfillClaim::occurredOn)
                .thenComparing(BackfillClaim::backfillId));
        Map<String, ReplayState> states = new LinkedHashMap<>();
        for (BackfillClaim claim : claims) {
            if (claim.occurredOn().isAfter(asOf)) {
                break;
            }
            replayTransition(states, claim);
        }
        Map<String, ProjectionNodeState> result = new LinkedHashMap<>();
        int dormancyYears = Params.defaults().dormancyTriggerYears();
        states.forEach((nodeCode, state) -> {
            boolean dormant = state.programEndDate() != null
                    && !asOf.isBefore(state.programEndDate().plusYears(dormancyYears));
            result.put(nodeCode, new ProjectionNodeState(
                    state.level(), dormant ? "DORMANT" : "ACTIVE",
                    state.programEndDate()));
        });
        return Map.copyOf(result);
    }

    private String fingerprint(
            String datasetSha256,
            String nodeSetVersion,
            String rubricVersion,
            Params params,
            CapabilityGraph graph) {
        if (datasetSha256 == null || !SHA256.matcher(datasetSha256).matches()) {
            throw new IllegalArgumentException("datasetSha256 must be lowercase SHA-256");
        }
        return projectorVersion + ":" + datasetSha256 + ":"
                + requireToken(nodeSetVersion, "nodeSetVersion") + ":"
                + requireToken(rubricVersion, "rubricVersion") + ":"
                + params.version() + ":" + graph.version() + ":"
                + graph.declaredSha256();
    }

    private static String requireToken(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 80 || value.contains("|")) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value.trim();
    }

    private ProjectionMarker readMarker() {
        String value = repository.findOpsState(MARKER_KEY)
                .map(state -> state.value())
                .orElse(null);
        if (value == null) {
            return null;
        }
        int separator = value.lastIndexOf('|');
        if (separator <= 0 || separator == value.length() - 1) {
            return null;
        }
        try {
            return new ProjectionMarker(
                    value.substring(0, separator),
                    LocalDate.parse(value.substring(separator + 1)));
        } catch (RuntimeException invalidMarker) {
            return null;
        }
    }

    private static void assertCompatible(
            SnapshotRow existing,
            double rawReadiness,
            double effectiveReadiness,
            double logit,
            String paramsVersion,
            String graphVersion) {
        if (Math.abs(existing.readiness() - effectiveReadiness) > READINESS_TOLERANCE
                || existing.rawReadiness() == null
                || Math.abs(existing.rawReadiness() - rawReadiness) > READINESS_TOLERANCE
                || Math.abs(existing.logitClipped() - logit) > LOGIT_TOLERANCE
                || !paramsVersion.equals(existing.paramsVersion())
                || !Objects.equals(graphVersion, existing.graphVersion())) {
            throw new IllegalStateException(
                    "weekly projection conflicts with existing operational snapshot for pillar "
                            + existing.pillar() + " on " + existing.snapshotDate());
        }
    }

    private static void replayTransition(
            Map<String, ReplayState> states, BackfillClaim claim) {
        ReplayState previous = states.getOrDefault(claim.nodeCode(), ReplayState.initial());
        if ("PROGRAM_CANCELLATION".equals(claim.eventType())) {
            if (claim.programEndEffect() == ProgramEndEffect.CAPABILITY_PROGRAM_END) {
                states.put(claim.nodeCode(), new ReplayState(
                        previous.level(), claim.occurredOn()));
            }
            return;
        }
        if (claim.claimedLevel() == null || NON_STATE_EVENTS.contains(claim.eventType())) {
            return;
        }
        if ("ROLLBACK".equals(claim.eventType())) {
            if (claim.claimedLevel() < previous.level()) {
                states.put(claim.nodeCode(), new ReplayState(
                        claim.claimedLevel(), previous.programEndDate()));
            }
            return;
        }
        states.put(claim.nodeCode(), new ReplayState(
                Math.max(previous.level(), claim.claimedLevel()), null));
    }

    private static List<NodeRow> materializeNodes(
            List<NodeRow> nodes,
            Map<String, ReplayState> states,
            Params params,
            LocalDate asOf) {
        List<NodeRow> replayNodes = new ArrayList<>(nodes.size());
        for (NodeRow node : nodes) {
            ReplayState state = states.getOrDefault(node.code(), ReplayState.initial());
            LocalDate dormantSince = state.programEndDate() == null
                    ? null : state.programEndDate().plusYears(params.dormancyTriggerYears());
            boolean dormant = dormantSince != null && !asOf.isBefore(dormantSince);
            replayNodes.add(new NodeRow(
                    node.id(), node.code(), node.pillar(), node.nameKo(), node.scaleType(),
                    state.level(), state.level() == 0 ? null : node.verificationLevel(),
                    dormant ? "DORMANT" : "ACTIVE",
                    dormant ? dormantSince : null,
                    state.programEndDate(), node.weight(), node.integrationNode(),
                    node.description(), node.nodeSetVersion()));
        }
        return List.copyOf(replayNodes);
    }

    public record ProjectionSummary(
            LocalDate firstMonday,
            LocalDate throughMonday,
            int insertedRows,
            boolean rebuilt) {
    }

    record ProjectionNodeState(
            int level,
            String status,
            LocalDate programEndDate) {
    }

    private record ProjectionMarker(String fingerprint, LocalDate through) {
    }

    private record SnapshotKey(int pillar, LocalDate date) {
    }

    private record ReplayState(int level, LocalDate programEndDate) {

        private static ReplayState initial() {
            return new ReplayState(0, null);
        }
    }
}
