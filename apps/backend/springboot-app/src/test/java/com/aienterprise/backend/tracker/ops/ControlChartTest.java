package com.aienterprise.backend.tracker.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ControlChartTest {

    @Test
    void fewerThanFourteenPopulatedDaysIsInsufficient() {
        var result = ControlChart.evaluate(
                repeat(0.5, 13), 0.9, 1, ControlChart.MetricKind.RATIO);

        assertEquals(ControlChart.Status.INSUFFICIENT_DATA, result.status());
        assertEquals(13, result.sampleDays());
        assertFalse(result.violation());
        assertEquals(0, result.consecutiveViolations());
        assertNull(result.baselineMean());
    }

    @Test
    void exactThreeSigmaBoundaryIsNormalAndOutsideIsWarning() {
        List<Double> baseline = new ArrayList<>();
        baseline.addAll(repeat(0.0, 7));
        baseline.addAll(repeat(2.0, 7));

        var boundary = ControlChart.evaluate(
                baseline, 4.0, 0, ControlChart.MetricKind.NON_NEGATIVE);
        var outside = ControlChart.evaluate(
                baseline, 4.01, 0, ControlChart.MetricKind.NON_NEGATIVE);

        assertEquals(1.0, boundary.baselineMean(), 1e-12);
        assertEquals(-2.0, boundary.lowerBound(), 1e-12);
        assertEquals(4.0, boundary.upperBound(), 1e-12);
        assertEquals(ControlChart.Status.OK, boundary.status());
        assertEquals(ControlChart.Status.WARNING, outside.status());
        assertTrue(outside.violation());
        assertEquals(1, outside.consecutiveViolations());
    }

    @Test
    void secondConsecutiveViolationTriggersAndNormalDayResets() {
        var first = ControlChart.evaluate(
                repeat(1.0, 14), 2.0, 0, ControlChart.MetricKind.NON_NEGATIVE);
        var second = ControlChart.evaluate(
                repeat(1.0, 14), 2.0, first.consecutiveViolations(),
                ControlChart.MetricKind.NON_NEGATIVE);
        var recovered = ControlChart.evaluate(
                repeat(1.0, 14), 1.0, second.consecutiveViolations(),
                ControlChart.MetricKind.NON_NEGATIVE);

        assertEquals(ControlChart.Status.WARNING, first.status());
        assertEquals(ControlChart.Status.TRIGGERED, second.status());
        assertEquals(2, second.consecutiveViolations());
        assertEquals(ControlChart.Status.OK, recovered.status());
        assertEquals(0, recovered.consecutiveViolations());
    }

    @Test
    void ratioBoundsAreClampedToZeroAndOne() {
        var low = ControlChart.evaluate(
                alternating(0.0, 0.2), 0.1, 0, ControlChart.MetricKind.RATIO);
        var high = ControlChart.evaluate(
                alternating(0.8, 1.0), 0.9, 0, ControlChart.MetricKind.RATIO);

        assertEquals(0.0, low.lowerBound(), 1e-12);
        assertEquals(1.0, high.upperBound(), 1e-12);
    }

    @Test
    void zeroVarianceTreatsAnyChangeAsViolation() {
        var same = ControlChart.evaluate(
                repeat(3.0, 14), 3.0, 0, ControlChart.MetricKind.NON_NEGATIVE);
        var changed = ControlChart.evaluate(
                repeat(3.0, 14), 3.000001, 0, ControlChart.MetricKind.NON_NEGATIVE);

        assertEquals(ControlChart.Status.OK, same.status());
        assertEquals(ControlChart.Status.WARNING, changed.status());
    }

    private static List<Double> repeat(double value, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(ignored -> value)
                .toList();
    }

    private static List<Double> alternating(double first, double second) {
        return java.util.stream.IntStream.range(0, 14)
                .mapToObj(index -> index % 2 == 0 ? first : second)
                .toList();
    }
}
