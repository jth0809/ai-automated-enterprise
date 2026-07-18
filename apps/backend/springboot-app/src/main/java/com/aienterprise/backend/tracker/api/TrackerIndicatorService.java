package com.aienterprise.backend.tracker.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

/** Deterministically separates present-readiness and projected-ETA bottlenecks. */
public final class TrackerIndicatorService {

    static final double READINESS_TOLERANCE = 1e-9;
    static final double ETA_TOLERANCE_YEARS = .05;
    private static final List<Integer> PILLARS = List.of(1, 2, 3, 4, 5, 6);

    public TrackerIndicators derive(List<SnapshotRow> snapshots) {
        Map<Integer, SnapshotRow> byPillar = new LinkedHashMap<>();
        for (SnapshotRow row : List.copyOf(Objects.requireNonNull(
                snapshots, "snapshots"))) {
            Objects.requireNonNull(row, "snapshot");
            if (!PILLARS.contains(row.pillar())) {
                throw new IllegalArgumentException(
                        "snapshot pillar must be between 1 and 6");
            }
            if (byPillar.putIfAbsent(row.pillar(), row) != null) {
                throw new IllegalArgumentException(
                        "duplicate snapshot pillar " + row.pillar());
            }
            if (!Double.isFinite(row.readiness())
                    || row.etaYear() != null && !Double.isFinite(row.etaYear())) {
                throw new IllegalArgumentException("snapshot values must be finite");
            }
        }

        List<Integer> missing = PILLARS.stream()
                .filter(pillar -> !byPillar.containsKey(pillar))
                .toList();
        if (!missing.isEmpty()) {
            return TrackerIndicators.incomplete(missing);
        }

        List<SnapshotRow> ordered = PILLARS.stream().map(byPillar::get).toList();
        SnapshotRow first = ordered.getFirst();
        if (first.snapshotDate() == null
                || ordered.stream().anyMatch(row -> !sameMetadata(first, row))) {
            return TrackerIndicators.incomplete(List.of());
        }

        double minimumReadiness = ordered.stream()
                .mapToDouble(SnapshotRow::readiness).min().orElseThrow();
        List<Integer> readinessBottlenecks = ordered.stream()
                .filter(row -> Math.abs(row.readiness() - minimumReadiness)
                        <= READINESS_TOLERANCE)
                .map(SnapshotRow::pillar)
                .sorted()
                .toList();

        List<Integer> unresolved = ordered.stream()
                .filter(row -> row.etaYear() == null)
                .map(SnapshotRow::pillar)
                .sorted()
                .toList();
        List<Integer> etaBottlenecks = unresolved.isEmpty()
                ? finiteEtaBottlenecks(ordered)
                : unresolved;

        return new TrackerIndicators(
                TrackerIndicators.Status.COMPLETE,
                readinessBottlenecks, etaBottlenecks, unresolved, List.of(),
                first.snapshotDate(), first.paramsVersion(), first.graphVersion());
    }

    private static List<Integer> finiteEtaBottlenecks(List<SnapshotRow> snapshots) {
        double maximumEta = snapshots.stream()
                .map(SnapshotRow::etaYear)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        List<Integer> result = new ArrayList<>();
        snapshots.stream()
                .filter(row -> Math.abs(row.etaYear() - maximumEta)
                        <= ETA_TOLERANCE_YEARS)
                .map(SnapshotRow::pillar)
                .sorted()
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static boolean sameMetadata(SnapshotRow expected, SnapshotRow actual) {
        LocalDate date = expected.snapshotDate();
        return date.equals(actual.snapshotDate())
                && Objects.equals(expected.paramsVersion(), actual.paramsVersion())
                && Objects.equals(expected.graphVersion(), actual.graphVersion());
    }
}
