package com.aienterprise.backend.tracker.prediction;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

/** Cutoff-local Gamma–Poisson-equivalent node-advance hazard estimator. */
public final class HazardEstimator {

    public static final LocalDate EXPOSURE_START = LocalDate.of(1957, 1, 7);
    private static final double DAYS_PER_YEAR = 365.2425;
    private static final Set<String> NON_STATE_EVENTS = Set.of(
            "SETBACK", "PROGRAM_CANCELLATION", "ANNOUNCEMENT_ONLY",
            "RETROSPECTIVE");

    public Result estimate(
            List<NodeRow> inputNodes,
            List<BackfillClaim> inputClaims,
            LocalDate cutoff,
            int dormancyTriggerYears,
            HazardParameters parameters) {
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(parameters, "parameters");
        if (cutoff.isBefore(EXPOSURE_START)
                || dormancyTriggerYears < 1 || dormancyTriggerYears > 100) {
            throw new IllegalArgumentException("invalid hazard cutoff or dormancy trigger");
        }

        List<NodeRow> nodes = Objects.requireNonNull(inputNodes, "nodes").stream()
                .sorted(Comparator.comparing(NodeRow::code))
                .toList();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("hazard nodes are required");
        }
        Map<String, State> states = new LinkedHashMap<>();
        for (NodeRow node : nodes) {
            if (node.code() == null || node.code().isBlank()
                    || node.pillar() < 1 || node.pillar() > 6
                    || states.put(node.code(), new State(node)) != null) {
                throw new IllegalArgumentException("invalid hazard node registry");
            }
        }

        List<BackfillClaim> claims = new ArrayList<>(
                Objects.requireNonNull(inputClaims, "claims"));
        claims.sort(Comparator.comparing(BackfillClaim::occurredOn)
                .thenComparing(BackfillClaim::backfillId));
        for (BackfillClaim claim : claims) {
            if (claim.occurredOn() == null
                    || claim.occurredOn().isBefore(EXPOSURE_START)
                    || !states.containsKey(claim.nodeCode())) {
                throw new IllegalArgumentException("invalid hazard claim registry");
            }
            if (claim.occurredOn().isAfter(cutoff)) {
                continue;
            }
            State state = states.get(claim.nodeCode());
            state.advanceTo(claim.occurredOn(), dormancyTriggerYears);
            state.apply(claim);
        }
        states.values().forEach(state ->
                state.advanceTo(cutoff, dormancyTriggerYears));

        Map<Integer, Integer> pillarAdvances = new LinkedHashMap<>();
        Map<Integer, Double> pillarExposure = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            pillarAdvances.put(pillar, 0);
            pillarExposure.put(pillar, 0.0);
        }
        states.values().forEach(state -> {
            int pillar = state.node.pillar();
            pillarAdvances.merge(pillar, state.advances, Integer::sum);
            pillarExposure.merge(pillar, state.exposureYears, Double::sum);
        });

        Map<Integer, Double> pillarRates = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            double exposure = pillarExposure.get(pillar);
            double rate = exposure == 0
                    ? 0 : pillarAdvances.get(pillar) / exposure;
            pillarRates.put(pillar, rate);
        }

        List<NodeHazard> results = new ArrayList<>();
        Map<String, NodeHazard> byCode = new LinkedHashMap<>();
        for (NodeRow node : nodes) {
            State state = states.get(node.code());
            double pillarRate = pillarRates.get(node.pillar());
            double nodeRate = (state.advances
                    + parameters.kappaNodeYears() * pillarRate)
                    / (state.exposureYears + parameters.kappaNodeYears());
            boolean eligible = "ACTIVE".equals(state.status)
                    && state.level >= 0 && state.level <= 7;
            NodeHazard value = new NodeHazard(
                    node, state.level, state.status, state.advances,
                    state.exposureYears, pillarRate, nodeRate, eligible);
            results.add(value);
            byCode.put(node.code(), value);
        }
        return new Result(cutoff, results, byCode, pillarRates);
    }

    static double rawProbability(double annualRate, double horizonYears) {
        if (!Double.isFinite(annualRate) || annualRate < 0
                || !Double.isFinite(horizonYears) || horizonYears <= 0) {
            throw new IllegalArgumentException("invalid hazard probability input");
        }
        return 1 - Math.exp(-annualRate * horizonYears);
    }

    static double boundedProbability(
            double annualRate,
            double horizonYears,
            HazardParameters parameters) {
        double raw = rawProbability(annualRate, horizonYears);
        return Math.max(parameters.probabilityFloor(),
                Math.min(parameters.probabilityCeiling(), raw));
    }

    public record Result(
            LocalDate cutoff,
            List<NodeHazard> nodes,
            Map<String, NodeHazard> byCode,
            Map<Integer, Double> pillarRates) {

        public Result {
            cutoff = Objects.requireNonNull(cutoff, "cutoff");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            byCode = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(byCode, "byCode")));
            pillarRates = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(pillarRates, "pillarRates")));
        }
    }

    public record NodeHazard(
            NodeRow node,
            int currentLevel,
            String currentStatus,
            int advances,
            double exposureYears,
            double pillarRate,
            double nodeRate,
            boolean eligible) {

        public NodeHazard {
            node = Objects.requireNonNull(node, "node");
            if (currentLevel < 0 || currentLevel > 9
                    || currentStatus == null || currentStatus.isBlank()
                    || advances < 0
                    || !nonnegative(exposureYears)
                    || !nonnegative(pillarRate)
                    || !nonnegative(nodeRate)
                    || eligible && (!"ACTIVE".equals(currentStatus)
                            || currentLevel > 7)) {
                throw new IllegalArgumentException("invalid node hazard");
            }
        }

        private static boolean nonnegative(double value) {
            return Double.isFinite(value) && value >= 0;
        }
    }

    private static final class State {
        private final NodeRow node;
        private LocalDate cursor = EXPOSURE_START;
        private int level;
        private String status = "ACTIVE";
        private LocalDate programEndDate;
        private int advances;
        private double exposureYears;

        private State(NodeRow node) {
            this.node = node;
        }

        private void advanceTo(LocalDate target, int dormancyTriggerYears) {
            if (target.isBefore(cursor)) {
                throw new IllegalArgumentException("hazard claims are not time ordered");
            }
            LocalDate dormancyDate = programEndDate == null
                    ? null : programEndDate.plusYears(dormancyTriggerYears);
            if ("ACTIVE".equals(status) && dormancyDate != null
                    && !dormancyDate.isAfter(target)) {
                exposeUntil(dormancyDate);
                status = "DORMANT";
            }
            exposeUntil(target);
        }

        private void exposeUntil(LocalDate target) {
            if (target.isAfter(cursor) && "ACTIVE".equals(status) && level < 8) {
                exposureYears += ChronoUnit.DAYS.between(cursor, target)
                        / DAYS_PER_YEAR;
            }
            cursor = target;
        }

        private void apply(BackfillClaim claim) {
            if ("PROGRAM_CANCELLATION".equals(claim.eventType())) {
                if (claim.programEndEffect()
                        == ProgramEndEffect.CAPABILITY_PROGRAM_END) {
                    programEndDate = claim.occurredOn();
                }
                return;
            }
            if (claim.claimedLevel() == null
                    || NON_STATE_EVENTS.contains(claim.eventType())) {
                return;
            }
            int claimed = claim.claimedLevel();
            if (claimed < 0 || claimed > 9) {
                throw new IllegalArgumentException("invalid claimed hazard level");
            }
            if ("ROLLBACK".equals(claim.eventType())) {
                if (claimed < level) {
                    level = claimed;
                }
                return;
            }
            if (claimed > level) {
                advances++;
                level = claimed;
            }
            status = "ACTIVE";
            programEndDate = null;
        }
    }
}
