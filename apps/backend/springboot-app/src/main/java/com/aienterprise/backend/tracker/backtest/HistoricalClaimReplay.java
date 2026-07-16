package com.aienterprise.backend.tracker.backtest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aienterprise.backend.tracker.backfill.BackfillClaim;
import com.aienterprise.backend.tracker.backfill.ProgramEndEffect;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.EffectiveReadinessEngine;
import com.aienterprise.backend.tracker.math.LogitEta;
import com.aienterprise.backend.tracker.math.Params;
import com.aienterprise.backend.tracker.math.StateChangeEvent;

public final class HistoricalClaimReplay {

    private static final LocalDate FIRST_MONDAY = LocalDate.of(1957, 1, 7);
    private static final Set<String> NON_STATE_EVENTS = Set.of(
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY",
            "RETROSPECTIVE");

    private final List<NodeRow> templates;
    private final Map<String, NodeRow> templatesByCode;
    private final List<BackfillClaim> claims;
    private final EffectiveReadinessEngine readinessEngine;

    public HistoricalClaimReplay(
            List<NodeRow> templates,
            List<BackfillClaim> claims) {
        this(templates, claims, new EffectiveReadinessEngine());
    }

    HistoricalClaimReplay(
            List<NodeRow> inputTemplates,
            List<BackfillClaim> inputClaims,
            EffectiveReadinessEngine readinessEngine) {
        this.templates = Objects.requireNonNull(inputTemplates, "templates").stream()
                .sorted(Comparator.comparing(NodeRow::code))
                .toList();
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("historical replay nodes are required");
        }
        Map<String, NodeRow> byCode = new LinkedHashMap<>();
        for (NodeRow node : templates) {
            if (node.pillar() < 1 || node.pillar() > 6
                    || byCode.put(node.code(), node) != null) {
                throw new IllegalArgumentException("invalid historical replay node registry");
            }
        }
        this.templatesByCode = Collections.unmodifiableMap(byCode);
        this.claims = Objects.requireNonNull(inputClaims, "claims").stream()
                .sorted(Comparator.comparing(BackfillClaim::occurredOn)
                        .thenComparing(BackfillClaim::backfillId))
                .toList();
        for (BackfillClaim claim : this.claims) {
            if (claim.occurredOn() == null
                    || !templatesByCode.containsKey(claim.nodeCode())) {
                throw new IllegalArgumentException(
                        "claim references an unknown replay node: " + claim.nodeCode());
            }
        }
        this.readinessEngine = Objects.requireNonNull(readinessEngine, "readinessEngine");
    }

    public Frame frame(LocalDate asOf, Params params) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(params, "params");
        Map<String, MutableState> states = new LinkedHashMap<>();
        templates.forEach(node -> states.put(node.code(), new MutableState()));
        List<StateChangeEvent> changes = new ArrayList<>();
        long[] nextEventId = {1};

        for (BackfillClaim claim : claims) {
            if (claim.occurredOn().isAfter(asOf)) {
                break;
            }
            applyPendingDormancy(
                    states, claim.occurredOn(), params, changes, nextEventId);
            applyClaim(states.get(claim.nodeCode()), claim, changes, nextEventId);
        }
        applyPendingDormancy(states, asOf, params, changes, nextEventId);

        List<NodeRow> nodes = templates.stream()
                .map(node -> materialize(node, states.get(node.code())))
                .toList();
        Map<Integer, List<StateChangeEvent>> byPillar = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            int currentPillar = pillar;
            byPillar.put(pillar, changes.stream()
                    .filter(change -> change.pillar() == currentPillar)
                    .sorted(Comparator.comparing(StateChangeEvent::occurredOn)
                            .thenComparingLong(StateChangeEvent::eventId))
                    .toList());
        }
        return new Frame(asOf, nodes, byPillar);
    }

    public Map<Integer, List<SnapshotRow>> weeklyHistory(
            LocalDate through,
            Params params,
            CapabilityGraph graph) {
        Objects.requireNonNull(through, "through");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(graph, "graph");
        if (through.getDayOfWeek() != DayOfWeek.MONDAY
                || through.isBefore(FIRST_MONDAY)) {
            throw new IllegalArgumentException(
                    "weekly history cutoff must be a Monday on or after 1957-01-07");
        }
        Map<Integer, List<SnapshotRow>> rows = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            rows.put(pillar, new ArrayList<>());
        }
        long id = 1;
        for (LocalDate date = FIRST_MONDAY;
                !date.isAfter(through); date = date.plusWeeks(1)) {
            Frame frame = frame(date, params);
            var readiness = readinessEngine.calculate(
                    frame.nodes(), graph, params, date);
            for (int pillar = 1; pillar <= 6; pillar++) {
                double raw = readiness.rawPillarReadiness()
                        .getOrDefault(pillar, 0.0);
                double effective = readiness.effectivePillarReadiness()
                        .getOrDefault(pillar, 0.0);
                rows.get(pillar).add(new SnapshotRow(
                        id++, pillar, date, effective,
                        LogitEta.logitClipped(effective, params.epsilon()),
                        null, null, null, null, null, null, null, null,
                        params.version(), raw, graph.version()));
            }
        }
        Map<Integer, List<SnapshotRow>> result = new LinkedHashMap<>();
        rows.forEach((pillar, values) -> result.put(pillar, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }

    private void applyPendingDormancy(
            Map<String, MutableState> states,
            LocalDate through,
            Params params,
            List<StateChangeEvent> changes,
            long[] nextEventId) {
        List<PendingDormancy> pending = new ArrayList<>();
        states.forEach((nodeCode, state) -> {
            if (state.programEndDate != null && !"DORMANT".equals(state.status)) {
                LocalDate date = state.programEndDate.plusYears(
                        params.dormancyTriggerYears());
                if (!date.isAfter(through)) {
                    pending.add(new PendingDormancy(nodeCode, date));
                }
            }
        });
        pending.sort(Comparator.comparing(PendingDormancy::date)
                .thenComparing(PendingDormancy::nodeCode));
        for (PendingDormancy item : pending) {
            MutableState state = states.get(item.nodeCode());
            LocalDate expected = state.programEndDate == null
                    ? null : state.programEndDate.plusYears(
                            params.dormancyTriggerYears());
            if (!item.date().equals(expected) || "DORMANT".equals(state.status)) {
                continue;
            }
            String previous = state.status;
            state.status = "DORMANT";
            state.dormantSince = item.date();
            NodeRow node = templatesByCode.get(item.nodeCode());
            changes.add(new StateChangeEvent(
                    nextEventId[0]++, node.pillar(), item.date(), "DORMANCY",
                    state.level, state.level, previous, state.status));
        }
    }

    private void applyClaim(
            MutableState state,
            BackfillClaim claim,
            List<StateChangeEvent> changes,
            long[] nextEventId) {
        if ("PROGRAM_CANCELLATION".equals(claim.eventType())) {
            if (claim.programEndEffect() == ProgramEndEffect.CAPABILITY_PROGRAM_END) {
                state.programEndDate = claim.occurredOn();
            }
            return;
        }
        if (claim.claimedLevel() == null || NON_STATE_EVENTS.contains(claim.eventType())) {
            return;
        }

        int previousLevel = state.level;
        String previousStatus = state.status;
        if ("ROLLBACK".equals(claim.eventType())) {
            if (claim.claimedLevel() < state.level) {
                state.level = claim.claimedLevel();
            }
        } else {
            state.level = Math.max(state.level, claim.claimedLevel());
            state.programEndDate = null;
            state.dormantSince = null;
            state.status = "ACTIVE";
        }
        if (state.level != previousLevel || !state.status.equals(previousStatus)) {
            NodeRow node = templatesByCode.get(claim.nodeCode());
            changes.add(new StateChangeEvent(
                    nextEventId[0]++, node.pillar(), claim.occurredOn(),
                    claim.eventType(), previousLevel, state.level,
                    previousStatus, state.status));
        }
    }

    private static NodeRow materialize(NodeRow template, MutableState state) {
        return new NodeRow(
                template.id(), template.code(), template.pillar(), template.nameKo(),
                template.scaleType(), state.level,
                state.level == 0 ? null : template.verificationLevel(),
                state.status, state.dormantSince, state.programEndDate,
                template.weight(), template.integrationNode(), template.description(),
                template.nodeSetVersion());
    }

    public record Frame(
            LocalDate asOf,
            List<NodeRow> nodes,
            Map<Integer, List<StateChangeEvent>> stateChanges) {

        public Frame {
            asOf = Objects.requireNonNull(asOf, "asOf");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            Map<Integer, List<StateChangeEvent>> copied = new LinkedHashMap<>();
            Objects.requireNonNull(stateChanges, "stateChanges")
                    .forEach((pillar, values) -> copied.put(pillar, List.copyOf(values)));
            stateChanges = Collections.unmodifiableMap(copied);
        }
    }

    private static final class MutableState {
        private int level;
        private String status = "ACTIVE";
        private LocalDate programEndDate;
        private LocalDate dormantSince;
    }

    private record PendingDormancy(String nodeCode, LocalDate date) {
    }
}
