package com.aienterprise.backend.tracker.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DeadmanMonitor {

    private static final int MIN_INTERVAL_SAMPLES = 3;
    private static final int MAX_PUBLICATION_TIMESTAMPS = 64;

    private DeadmanMonitor() {
    }

    public static Result evaluate(List<Instant> publicationTimes, Instant observedAt) {
        if (publicationTimes == null || observedAt == null) {
            throw new IllegalArgumentException("publication times and observation time are required");
        }
        if (publicationTimes.size() > MAX_PUBLICATION_TIMESTAMPS) {
            throw new IllegalArgumentException("deadman input exceeds 64 publication timestamps");
        }

        List<Instant> ordered = publicationTimes.stream()
                .distinct()
                .sorted()
                .toList();
        if (ordered.isEmpty()) {
            return new Result(Status.INSUFFICIENT_DATA, 0, null, null);
        }

        List<Double> intervalSeconds = new ArrayList<>(Math.max(0, ordered.size() - 1));
        for (int index = 1; index < ordered.size(); index++) {
            intervalSeconds.add(secondsBetween(ordered.get(index - 1), ordered.get(index)));
        }
        double silenceSeconds = Math.max(0.0, secondsBetween(ordered.getLast(), observedAt));
        if (intervalSeconds.size() < MIN_INTERVAL_SAMPLES) {
            return new Result(
                    Status.INSUFFICIENT_DATA,
                    intervalSeconds.size(),
                    null,
                    silenceSeconds / 3_600.0);
        }

        intervalSeconds.sort(Comparator.naturalOrder());
        double medianSeconds = median(intervalSeconds);
        Status status = silenceSeconds <= 2.0 * medianSeconds
                ? Status.OK : Status.ALERT;
        return new Result(
                status,
                intervalSeconds.size(),
                medianSeconds / 3_600.0,
                silenceSeconds / 3_600.0);
    }

    private static double secondsBetween(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private static double median(List<Double> sortedValues) {
        int middle = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) {
            return sortedValues.get(middle);
        }
        return (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2.0;
    }

    public enum Status {
        INSUFFICIENT_DATA,
        OK,
        ALERT
    }

    public record Result(
            Status status,
            int intervalSamples,
            Double medianIntervalHours,
            Double silenceHours) {
    }
}
