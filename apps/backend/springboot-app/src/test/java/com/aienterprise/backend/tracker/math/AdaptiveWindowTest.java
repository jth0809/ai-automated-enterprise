package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class AdaptiveWindowTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 16);
    private final AdaptiveWindow adaptive = new AdaptiveWindow();

    @Test
    void usesMedianPositiveIntervalsAndSixTimesCadence() {
        AdaptiveWindow.Selection selection = adaptive.select(
                1,
                List.of(event(1, "2000-01-01"), event(2, "2001-01-01"),
                        event(3, "2002-01-01")),
                null, AS_OF, Params.defaults());

        assertEquals(6, selection.windowYears());
        assertEquals(0, selection.eventsInWindow());
        assertEquals(1.0, selection.medianIntervalYears(), 0.01);
        assertNull(selection.regimeStart());
    }

    @Test
    void clampsShortAndLongCadenceToFourAndFifteenYears() {
        assertEquals(4, adaptive.select(
                1,
                List.of(event(1, "2024-01-01"), event(2, "2024-07-01"),
                        event(3, "2025-01-01")),
                null, AS_OF, Params.defaults()).windowYears());

        assertEquals(15, adaptive.select(
                1,
                List.of(event(1, "2015-01-01"), event(2, "2018-01-01"),
                        event(3, "2021-01-01")),
                null, AS_OF, Params.defaults()).windowYears());
    }

    @Test
    void sparseHistoryFallsBackToTenYearsAndDuplicateDatesDoNotCreateZeroGaps() {
        assertEquals(10, adaptive.select(
                1, List.of(event(1, "2020-01-01"), event(2, "2021-01-01")),
                null, AS_OF, Params.defaults()).windowYears());

        AdaptiveWindow.Selection duplicate = adaptive.select(
                1,
                List.of(event(1, "2020-01-01"), event(2, "2020-01-01"),
                        event(3, "2021-01-01"), event(4, "2022-01-01")),
                null, AS_OF, Params.defaults());
        assertEquals(6, duplicate.windowYears());
    }

    @Test
    void approvedRegimeBreakResetsIntervalsWindowCountsAndRollbackDates() {
        RegimeBreak regime = new RegimeBreak(
                7, 1, LocalDate.of(2020, 1, 1), 99, "params-v2");
        AdaptiveWindow.Selection selection = adaptive.select(
                1,
                List.of(
                        event(1, "2010-01-01"),
                        rollback(2, "2015-01-01"),
                        event(3, "2020-01-01"),
                        rollback(4, "2022-01-01"),
                        event(5, "2024-01-01")),
                regime, AS_OF, Params.defaults());

        assertEquals(LocalDate.of(2020, 1, 1), selection.regimeStart());
        assertEquals(12, selection.windowYears());
        assertEquals(3, selection.eventsInWindow());
        assertEquals(List.of(LocalDate.of(2022, 1, 1)), selection.rollbackDates());
        assertEquals(LocalDate.of(2020, 1, 1), selection.windowStart());
    }

    @Test
    void rejectsAnyFutureFeatureInsteadOfSilentlyLeakingIt() {
        assertThrows(IllegalArgumentException.class, () -> adaptive.select(
                1, List.of(event(1, "2027-01-01")),
                null, AS_OF, Params.defaults()));
        assertThrows(IllegalArgumentException.class, () -> adaptive.select(
                1, List.of(), new RegimeBreak(
                        1, 1, LocalDate.of(2027, 1, 1), 1, "params-v2"),
                AS_OF, Params.defaults()));
    }

    private static StateChangeEvent event(long id, String date) {
        return new StateChangeEvent(
                id, 1, LocalDate.parse(date), "FLIGHT_TEST",
                1, 2, "ACTIVE", "ACTIVE");
    }

    private static StateChangeEvent rollback(long id, String date) {
        return new StateChangeEvent(
                id, 1, LocalDate.parse(date), "ROLLBACK",
                5, 4, "ACTIVE", "ACTIVE");
    }
}
