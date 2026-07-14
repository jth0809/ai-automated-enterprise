package com.aienterprise.backend.tracker.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

class DeadmanMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void fewerThanThreePublicationIntervalsAreInsufficientData() {
        var result = DeadmanMonitor.evaluate(List.of(
                NOW.minus(4, ChronoUnit.HOURS),
                NOW.minus(3, ChronoUnit.HOURS),
                NOW.minus(2, ChronoUnit.HOURS)), NOW);

        assertEquals(DeadmanMonitor.Status.INSUFFICIENT_DATA, result.status());
        assertEquals(2, result.intervalSamples());
    }

    @Test
    void silenceExactlyTwiceTheMedianIntervalIsStillOk() {
        var result = DeadmanMonitor.evaluate(hourlyPublicationsEndingAt(
                NOW.minus(2, ChronoUnit.HOURS)), NOW);

        assertEquals(DeadmanMonitor.Status.OK, result.status());
        assertEquals(3, result.intervalSamples());
        assertEquals(1.0, result.medianIntervalHours());
        assertEquals(2.0, result.silenceHours());
    }

    @Test
    void silenceBeyondTwiceTheMedianIntervalAlerts() {
        Instant latest = NOW.minus(2, ChronoUnit.HOURS).minusSeconds(1);

        var result = DeadmanMonitor.evaluate(hourlyPublicationsEndingAt(latest), NOW);

        assertEquals(DeadmanMonitor.Status.ALERT, result.status());
    }

    private static List<Instant> hourlyPublicationsEndingAt(Instant latest) {
        return List.of(
                latest.minus(3, ChronoUnit.HOURS),
                latest.minus(2, ChronoUnit.HOURS),
                latest.minus(1, ChronoUnit.HOURS),
                latest);
    }
}
