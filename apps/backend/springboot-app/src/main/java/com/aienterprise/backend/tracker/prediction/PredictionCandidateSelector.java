package com.aienterprise.backend.tracker.prediction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

import com.aienterprise.backend.tracker.domain.NodeRow;

/** Deterministic 12/cohort and 2/pillar micro-prediction publication policy. */
public final class PredictionCandidateSelector {

    private static final double INFORMATION_MIN = 0.10;
    private static final double INFORMATION_MAX = 0.90;
    private static final double TIE_EPSILON = 1e-15;

    private final HazardParameters parameters;

    public PredictionCandidateSelector(HazardParameters parameters) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    public List<Candidate> select(
            List<HazardEstimator.NodeHazard> hazards,
            LocalDate issuedOn,
            Calibration calibration) {
        Objects.requireNonNull(hazards, "hazards");
        Objects.requireNonNull(issuedOn, "issuedOn");
        Objects.requireNonNull(calibration, "calibration");
        List<Candidate> ranked = hazards.stream()
                .filter(HazardEstimator.NodeHazard::eligible)
                .map(hazard -> candidate(hazard, issuedOn, calibration))
                .sorted(CANDIDATE_ORDER)
                .toList();

        Map<Integer, Integer> perPillar = new HashMap<>();
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : ranked) {
            if (selected.size() >= parameters.cohortLimit()) {
                break;
            }
            int count = perPillar.getOrDefault(candidate.pillar(), 0);
            if (count >= parameters.pillarLimit()) {
                continue;
            }
            selected.add(candidate);
            perPillar.put(candidate.pillar(), count + 1);
        }
        return List.copyOf(selected);
    }

    private Candidate candidate(
            HazardEstimator.NodeHazard hazard,
            LocalDate issuedOn,
            Calibration calibration) {
        HorizonProbability best = null;
        for (int horizon : parameters.horizonsMonths()) {
            double raw = HazardEstimator.rawProbability(
                    hazard.nodeRate(), horizon / 12.0);
            double calibrated = calibration.apply(raw);
            if (!Double.isFinite(calibrated)) {
                throw new IllegalArgumentException(
                        "calibration returned a non-finite probability");
            }
            double issued = clamp(calibrated);
            double information = issued * (1 - issued);
            HorizonProbability value = new HorizonProbability(
                    horizon, raw, calibrated, issued, information);
            if (best == null || information > best.information() + TIE_EPSILON
                    || Math.abs(information - best.information()) <= TIE_EPSILON
                            && horizon < best.horizonMonths()) {
                best = value;
            }
        }
        NodeRow node = hazard.node();
        LocalDate dueOn = issuedOn.plusMonths(best.horizonMonths());
        int targetLevel = hazard.currentLevel() + 1;
        String statement = node.nameKo() + "이 " + dueOn
                + "까지 검증된 수준 L" + targetLevel + " 이상에 도달한다.";
        InformationStatus status = best.issuedProbability() >= INFORMATION_MIN
                && best.issuedProbability() <= INFORMATION_MAX
                ? InformationStatus.INFORMATIVE
                : InformationStatus.LOW_INFORMATION;
        return new Candidate(
                node.id(), node.code(), node.nameKo(), node.pillar(),
                node.integrationNode(), node.weight(), hazard.currentLevel(),
                targetLevel, issuedOn, dueOn, best.horizonMonths(),
                best.rawProbability(), best.calibratedProbability(),
                best.issuedProbability(), best.information(), status,
                calibration.version(), statement, hazard.advances(),
                hazard.exposureYears(), hazard.pillarRate(), hazard.nodeRate());
    }

    private double clamp(double value) {
        return Math.max(parameters.probabilityFloor(),
                Math.min(parameters.probabilityCeiling(), value));
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparing((Candidate value) ->
                            value.informationStatus() == InformationStatus.INFORMATIVE
                                    ? 0 : 1)
                    .thenComparing(Candidate::informationScore,
                            Comparator.reverseOrder())
                    .thenComparing(Candidate::nodeWeight,
                            Comparator.reverseOrder())
                    .thenComparing(Candidate::nodeCode);

    public enum InformationStatus {
        INFORMATIVE,
        LOW_INFORMATION
    }

    public record Calibration(
            String version,
            DoubleUnaryOperator transform) {

        public Calibration {
            if (version == null || version.isBlank() || version.length() > 80) {
                throw new IllegalArgumentException("calibration version is required");
            }
            transform = Objects.requireNonNull(transform, "transform");
        }

        public static Calibration identity() {
            return new Calibration("identity-v1", value -> value);
        }

        double apply(double raw) {
            return transform.applyAsDouble(raw);
        }
    }

    public record Candidate(
            long nodeId,
            String nodeCode,
            String nodeName,
            int pillar,
            boolean integrationNode,
            double nodeWeight,
            int currentLevel,
            int targetLevel,
            LocalDate issuedOn,
            LocalDate dueOn,
            int horizonMonths,
            double rawProbability,
            double calibratedProbability,
            double issuedProbability,
            double informationScore,
            InformationStatus informationStatus,
            String calibrationVersion,
            String statement,
            int advanceCount,
            double exposureYears,
            double pillarRate,
            double nodeRate) {

        public Candidate {
            if (nodeId <= 0 || nodeCode == null || nodeCode.isBlank()
                    || nodeName == null || nodeName.isBlank()
                    || pillar < 1 || pillar > 6
                    || !Double.isFinite(nodeWeight) || nodeWeight <= 0
                    || currentLevel < 0 || currentLevel > 7
                    || targetLevel != currentLevel + 1
                    || issuedOn == null || dueOn == null || !dueOn.isAfter(issuedOn)
                    || !List.of(6, 12, 18, 24).contains(horizonMonths)
                    || !unit(rawProbability)
                    || !Double.isFinite(calibratedProbability)
                    || !unit(issuedProbability)
                    || !Double.isFinite(informationScore)
                    || informationScore < 0 || informationScore > 0.25
                    || informationStatus == null
                    || calibrationVersion == null || calibrationVersion.isBlank()
                    || statement == null || statement.isBlank()
                    || advanceCount < 0
                    || !nonnegative(exposureYears)
                    || !nonnegative(pillarRate)
                    || !nonnegative(nodeRate)) {
                throw new IllegalArgumentException("invalid prediction candidate");
            }
        }

        private static boolean unit(double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }

        private static boolean nonnegative(double value) {
            return Double.isFinite(value) && value >= 0;
        }
    }

    private record HorizonProbability(
            int horizonMonths,
            double rawProbability,
            double calibratedProbability,
            double issuedProbability,
            double information) {
    }
}
