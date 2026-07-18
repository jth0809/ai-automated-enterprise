package com.aienterprise.backend.tracker.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** One complete, data-derived interpretation of the latest six pillar snapshots. */
public record TrackerIndicators(
        Status status,
        List<Integer> readinessBottleneckPillars,
        List<Integer> etaBottleneckPillars,
        List<Integer> unresolvedEtaPillars,
        List<Integer> missingPillars,
        LocalDate snapshotDate,
        String paramsVersion,
        String graphVersion) {

    public TrackerIndicators {
        status = Objects.requireNonNull(status, "status");
        readinessBottleneckPillars = List.copyOf(Objects.requireNonNull(
                readinessBottleneckPillars, "readinessBottleneckPillars"));
        etaBottleneckPillars = List.copyOf(Objects.requireNonNull(
                etaBottleneckPillars, "etaBottleneckPillars"));
        unresolvedEtaPillars = List.copyOf(Objects.requireNonNull(
                unresolvedEtaPillars, "unresolvedEtaPillars"));
        missingPillars = List.copyOf(Objects.requireNonNull(
                missingPillars, "missingPillars"));
    }

    public Integer legacyBottleneckPillar() {
        return readinessBottleneckPillars.isEmpty()
                ? null : readinessBottleneckPillars.getFirst();
    }

    static TrackerIndicators incomplete(List<Integer> missingPillars) {
        return new TrackerIndicators(
                Status.INCOMPLETE_SNAPSHOT,
                List.of(), List.of(), List.of(), missingPillars,
                null, null, null);
    }

    public enum Status {
        COMPLETE,
        INCOMPLETE_SNAPSHOT
    }
}
