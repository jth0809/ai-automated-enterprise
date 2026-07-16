package com.aienterprise.backend.tracker.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class RightCensoredQuantilesTest {

    @Test
    void ranksCensoredMassAtPositiveInfinity() {
        RightCensoredQuantiles.Summary summary = RightCensoredQuantiles.summarize(
                List.of(8.0, 1.0, 3.0, 2.0), 6);

        assertEquals(1.0, summary.p10());
        assertNull(summary.p50());
        assertNull(summary.p90());
        assertEquals(0.6, summary.censoredFraction());
    }

    @Test
    void returnsFiniteRankOnlyWhenUnconditionalRankIsFinite() {
        RightCensoredQuantiles.Summary summary = RightCensoredQuantiles.summarize(
                List.of(8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0), 2);

        assertEquals(1.0, summary.p10());
        assertEquals(5.0, summary.p50());
        assertNull(summary.p90());
        assertEquals(0.2, summary.censoredFraction());
    }

    @Test
    void rejectsNonFiniteValuesAndEmptyPopulation() {
        assertThrows(IllegalArgumentException.class,
                () -> RightCensoredQuantiles.summarize(List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> RightCensoredQuantiles.summarize(List.of(Double.NaN), 0));
        assertThrows(IllegalArgumentException.class,
                () -> RightCensoredQuantiles.summarize(List.of(1.0), -1));
    }
}
