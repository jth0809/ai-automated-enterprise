package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

class MomentumServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 1);
    private final MomentumService service = new MomentumService();

    @Test
    void classifiesOnlyDisplayMomentumFromSuccessiveLogitChanges() {
        assertEquals(MomentumService.Status.ACCELERATING,
                service.classify(history(-2.0, -1.9, -1.6, -1.0), AS_OF));
        assertEquals(MomentumService.Status.STEADY,
                service.classify(history(-2.0, -1.8, -1.6, -1.4), AS_OF));
        assertEquals(MomentumService.Status.DECELERATING,
                service.classify(history(-2.0, -1.5, -1.2, -1.1), AS_OF));
    }

    @Test
    void sparseHistoryIsInsufficientAndFutureRowsFailClosed() {
        assertEquals(MomentumService.Status.INSUFFICIENT_DATA,
                service.classify(history(-2.0, -1.5, -1.0), AS_OF));

        List<SnapshotRow> future = List.of(snapshot(2023, -2.0),
                snapshot(2024, -1.8), snapshot(2025, -1.6),
                snapshot(2027, -1.4));
        assertThrows(IllegalArgumentException.class,
                () -> service.classify(future, AS_OF));
    }

    private static List<SnapshotRow> history(double... logits) {
        java.util.ArrayList<SnapshotRow> rows = new java.util.ArrayList<>();
        for (int index = 0; index < logits.length; index++) {
            rows.add(snapshot(2022 + index, logits[index]));
        }
        return rows;
    }

    private static SnapshotRow snapshot(int year, double logit) {
        double readiness = 1.0 / (1.0 + Math.exp(-logit));
        return new SnapshotRow(
                0, 1, LocalDate.of(year, 1, 1), readiness, logit,
                null, null, null, null, null, null, null, null,
                "params-v2", readiness, "graph-v1.0");
    }
}
