package com.aienterprise.backend.tracker.prediction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic Brier summaries with sample sizes at every public grouping. */
public record PredictionScorecard(List<Group> groups) {

    public PredictionScorecard {
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("scorecard needs an overall group");
        }
    }

    public static PredictionScorecard from(
            List<PredictionRepository.ScoredPrediction> predictions) {
        List<PredictionRepository.ScoredPrediction> values = List.copyOf(
                Objects.requireNonNull(predictions, "predictions"));
        List<Group> groups = new ArrayList<>();
        groups.add(group(GroupType.OVERALL, "ALL", values));
        addGroups(groups, GroupType.COHORT, values, value -> value.cohortKey());
        addGroups(groups, GroupType.HORIZON, values,
                value -> Integer.toString(value.horizonMonths()));
        addGroups(groups, GroupType.PILLAR, values,
                value -> Integer.toString(value.pillar()));
        return new PredictionScorecard(groups);
    }

    private static void addGroups(
            List<Group> target,
            GroupType type,
            List<PredictionRepository.ScoredPrediction> predictions,
            java.util.function.Function<PredictionRepository.ScoredPrediction,
                    String> classifier) {
        Map<String, List<PredictionRepository.ScoredPrediction>> grouped =
                new TreeMap<>(numericAwareComparator());
        for (PredictionRepository.ScoredPrediction prediction : predictions) {
            grouped.computeIfAbsent(classifier.apply(prediction), ignored ->
                    new ArrayList<>()).add(prediction);
        }
        grouped.forEach((key, values) -> target.add(group(type, key, values)));
    }

    private static Comparator<String> numericAwareComparator() {
        return (left, right) -> {
            try {
                return Integer.compare(Integer.parseInt(left),
                        Integer.parseInt(right));
            } catch (NumberFormatException ignored) {
                return left.compareTo(right);
            }
        };
    }

    private static Group group(
            GroupType type,
            String key,
            List<PredictionRepository.ScoredPrediction> predictions) {
        if (predictions.isEmpty()) {
            return new Group(type, key, 0, null, Status.INSUFFICIENT_DATA);
        }
        double mean = predictions.stream()
                .mapToDouble(PredictionRepository.ScoredPrediction::brier)
                .average().orElseThrow();
        return new Group(type, key, predictions.size(), mean, Status.OK);
    }

    public enum GroupType {
        OVERALL,
        COHORT,
        HORIZON,
        PILLAR
    }

    public enum Status {
        OK,
        INSUFFICIENT_DATA
    }

    public record Group(
            GroupType type,
            String key,
            int sampleCount,
            Double meanBrier,
            Status status) {

        public Group {
            if (type == null || key == null || key.isBlank()
                    || sampleCount < 0 || status == null
                    || sampleCount == 0 && (meanBrier != null
                            || status != Status.INSUFFICIENT_DATA)
                    || sampleCount > 0 && (meanBrier == null
                            || !Double.isFinite(meanBrier)
                            || meanBrier < 0 || meanBrier > 1
                            || status != Status.OK)) {
                throw new IllegalArgumentException("invalid scorecard group");
            }
        }
    }
}
