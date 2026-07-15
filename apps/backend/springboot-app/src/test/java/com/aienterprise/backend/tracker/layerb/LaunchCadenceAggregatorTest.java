package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.LayerBMetric;

class LaunchCadenceAggregatorTest {

    private final LaunchCadenceAggregator aggregator = new LaunchCadenceAggregator();
    private static final LocalDate ACCESSED = LocalDate.of(2026, 7, 14);

    private static LaunchRecord launch(String net, boolean successful) {
        return new LaunchRecord("id", "name", Instant.parse(net), "SpaceX",
                successful ? "Success" : "Failure", successful);
    }

    private static LaunchRecord launchWithStatus(String id, String status) {
        return new LaunchRecord(id, "name", Instant.parse("2024-06-01T00:00:00Z"),
                "Provider", status, "Success".equals(status));
    }

    private static LayerBMetric find(List<LayerBMetric> metrics, String code) {
        return metrics.stream().filter(m -> m.metricCode().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void computesCountAndSuccessRateForTheYearOnly() {
        List<LaunchRecord> launches = List.of(
                launch("2024-01-01T00:00:00Z", true),
                launch("2024-06-01T00:00:00Z", true),
                launch("2024-09-01T00:00:00Z", false),
                launch("2023-12-31T00:00:00Z", true));

        List<LayerBMetric> metrics = aggregator.aggregate(2024, launches, ACCESSED);

        assertEquals(2, metrics.size());
        LayerBMetric count = find(metrics, "ANNUAL_LAUNCH_COUNT");
        assertEquals(0, new BigDecimal("3").compareTo(count.value()));
        assertEquals("MEASURED", count.basis());
        assertEquals(LocalDate.of(2024, 12, 31), count.observedOn());
        LayerBMetric rate = find(metrics, "ANNUAL_LAUNCH_SUCCESS_RATE");
        assertEquals(0, new BigDecimal("66.67").compareTo(rate.value()));
        assertEquals("PERCENT", rate.unit());
    }

    @Test
    void emptyWhenNoLaunchesFallInTheYear() {
        assertTrue(aggregator.aggregate(2024, List.of(), ACCESSED).isEmpty());
        assertTrue(aggregator.aggregate(2024,
                List.of(launch("2020-01-01T00:00:00Z", true)), ACCESSED).isEmpty());
    }

    @Test
    void countsCompletedAttemptsWithoutTreatingPlannedOrCancelledEntriesAsLaunches() {
        List<LayerBMetric> metrics = aggregator.aggregate(2024, List.of(
                launchWithStatus("success", "Success"),
                launchWithStatus("failure", "Failure"),
                launchWithStatus("partial", "Partial Failure"),
                launchWithStatus("tbd", "TBD"),
                launchWithStatus("go", "Go"),
                launchWithStatus("cancelled", "Cancelled")), ACCESSED);

        assertEquals(0, new BigDecimal("3").compareTo(
                find(metrics, "ANNUAL_LAUNCH_COUNT").value()));
        assertEquals(0, new BigDecimal("33.33").compareTo(
                find(metrics, "ANNUAL_LAUNCH_SUCCESS_RATE").value()));
    }
}
