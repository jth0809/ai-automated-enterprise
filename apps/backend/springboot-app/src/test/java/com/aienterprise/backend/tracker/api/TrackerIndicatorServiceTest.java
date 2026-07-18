package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

class TrackerIndicatorServiceTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 7, 18);

    private final TrackerIndicatorService service = new TrackerIndicatorService();

    @Test
    void derivesDifferentReadinessAndEtaBottlenecks() {
        TrackerIndicators result = service.derive(List.of(
                snapshot(1, .40, 2072.1),
                snapshot(2, .30, 2085.1),
                snapshot(3, .147, 2086.7),
                snapshot(4, .20, 2090.0),
                snapshot(5, .50, 2064.5),
                snapshot(6, .25, 2089.8)));

        assertEquals(TrackerIndicators.Status.COMPLETE, result.status());
        assertEquals(List.of(3), result.readinessBottleneckPillars());
        assertEquals(List.of(4), result.etaBottleneckPillars());
        assertEquals(List.of(), result.unresolvedEtaPillars());
        assertEquals(List.of(), result.missingPillars());
        assertEquals(3, result.legacyBottleneckPillar());
        assertEquals(SNAPSHOT_DATE, result.snapshotDate());
        assertEquals("params-v2", result.paramsVersion());
        assertEquals("graph-v1.0", result.graphVersion());
    }

    @Test
    void preservesAllReadinessAndEtaTiesWithinDeclaredTolerance() {
        TrackerIndicators result = service.derive(List.of(
                snapshot(1, .40, 2072.1),
                snapshot(2, .1470000005, 2085.1),
                snapshot(3, .147, 2086.7),
                snapshot(4, .20, 2090.00),
                snapshot(5, .50, 2064.5),
                snapshot(6, .25, 2089.96)));

        assertEquals(List.of(2, 3), result.readinessBottleneckPillars());
        assertEquals(List.of(4, 6), result.etaBottleneckPillars());
        assertEquals(2, result.legacyBottleneckPillar());
    }

    @Test
    void unresolvedEtaPillarsTakePrecedenceOverFiniteEta() {
        TrackerIndicators result = service.derive(List.of(
                snapshot(1, .40, null),
                snapshot(2, .30, 2085.1),
                snapshot(3, .147, 2086.7),
                snapshot(4, .20, 2090.0),
                snapshot(5, .50, 2064.5),
                snapshot(6, .25, null)));

        assertEquals(List.of(1, 6), result.unresolvedEtaPillars());
        assertEquals(List.of(1, 6), result.etaBottleneckPillars());
    }

    @Test
    void incompleteSnapshotDoesNotFabricateBottlenecks() {
        List<SnapshotRow> partial = new ArrayList<>(List.of(
                snapshot(1, .40, 2072.1),
                snapshot(2, .30, 2085.1),
                snapshot(3, .147, 2086.7),
                snapshot(4, .20, 2090.0),
                snapshot(6, .25, 2089.8)));

        TrackerIndicators result = service.derive(partial);

        assertEquals(TrackerIndicators.Status.INCOMPLETE_SNAPSHOT, result.status());
        assertEquals(List.of(5), result.missingPillars());
        assertEquals(List.of(), result.readinessBottleneckPillars());
        assertEquals(List.of(), result.etaBottleneckPillars());
        assertEquals(List.of(), result.unresolvedEtaPillars());
        assertEquals(null, result.legacyBottleneckPillar());
    }

    @Test
    void mixedSnapshotDatesAreIncompleteAndDuplicatePillarsAreRejected() {
        List<SnapshotRow> mixed = new ArrayList<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            LocalDate date = pillar == 6 ? SNAPSHOT_DATE.minusWeeks(1) : SNAPSHOT_DATE;
            mixed.add(snapshot(pillar, .1 * pillar, 2070.0 + pillar, date));
        }

        assertEquals(TrackerIndicators.Status.INCOMPLETE_SNAPSHOT,
                service.derive(mixed).status());

        List<SnapshotRow> duplicate = new ArrayList<>(mixed);
        duplicate.add(snapshot(1, .99, 2100.0));
        assertThrows(IllegalArgumentException.class,
                () -> service.derive(duplicate));
    }

    private static SnapshotRow snapshot(int pillar, double readiness, Double etaYear) {
        return snapshot(pillar, readiness, etaYear, SNAPSHOT_DATE);
    }

    private static SnapshotRow snapshot(
            int pillar, double readiness, Double etaYear, LocalDate snapshotDate) {
        return new SnapshotRow(
                pillar, pillar, snapshotDate, readiness, 0.0,
                .01, .01, 4, 10, etaYear,
                etaYear == null ? null : etaYear - 2,
                etaYear == null ? null : etaYear + 2,
                etaYear, "params-v2", readiness, "graph-v1.0");
    }
}
