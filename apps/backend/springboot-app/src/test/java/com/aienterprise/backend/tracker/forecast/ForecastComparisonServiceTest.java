package com.aienterprise.backend.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;
import com.aienterprise.backend.tracker.transport.TransportProjection;

class ForecastComparisonServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

    private final TrackerRepository tracker = mock(TrackerRepository.class);
    private final TransportEconomicsRepository transport = mock(TransportEconomicsRepository.class);
    private final ForecastRepository forecasts = mock(ForecastRepository.class);
    private ForecastComparisonService service;

    @BeforeEach
    void setUp() {
        service = new ForecastComparisonService(
                tracker, transport, forecasts, CLOCK, false, false);
        when(tracker.findLatestSnapshot(0)).thenReturn(Optional.of(snapshot(2140.2)));
        when(transport.findLatestProjection()).thenReturn(Optional.of(projection(2030.4)));
        when(forecasts.findAllReferences()).thenReturn(references());
    }

    @Test
    void mapsOnlyComparableValuesAndKeepsSupportingIndicatorsLabeled() {
        when(forecasts.findLatestCrowdObservation("META-LANDING"))
                .thenReturn(Optional.of(observation("META-LANDING", "2044.8", TODAY)));
        when(forecasts.findLatestCrowdObservation("META-SETTLEMENT"))
                .thenReturn(Optional.of(observation("META-SETTLEMENT", "2090.0", TODAY)));

        ForecastComparison comparison = service.current();

        assertEquals("CURRENT", comparison.status());
        assertEquals("AUTHORIZATION_REQUIRED", comparison.crowdLiveStatus());
        assertEquals(3, comparison.rows().size());
        ForecastComparisonRow landing = comparison.rows().get(0);
        ForecastComparisonRow returning = comparison.rows().get(1);
        ForecastComparisonRow settlement = comparison.rows().get(2);

        assertEquals("NOT_APPLICABLE", landing.model().status());
        assertEquals("SUPPORTING", landing.transport().status());
        assertEquals(new BigDecimal("2030.4"), landing.transport().year());
        assertEquals("QUESTION_NOT_SELECTED", returning.crowd().status());
        assertEquals("NOT_APPLICABLE", returning.transport().status());
        assertEquals("DIRECT_PROXY", settlement.model().status());
        assertEquals(new BigDecimal("2140.2"), settlement.model().year());
        assertEquals("PROXY", settlement.crowd().relationKind());
        assertTrue(settlement.transport().detail().contains("$200/kg"));
    }

    @Test
    void missingCrowdValuesRemainPartialAndUndatedInstitutionsStayNull() {
        when(forecasts.findLatestCrowdObservation("META-LANDING"))
                .thenReturn(Optional.empty());
        when(forecasts.findLatestCrowdObservation("META-SETTLEMENT"))
                .thenReturn(Optional.empty());

        ForecastComparison comparison = service.current();

        assertEquals("PARTIAL", comparison.status());
        assertEquals("AWAITING_AUTHORIZATION", comparison.rows().get(0).crowd().status());
        assertNull(comparison.rows().get(0).crowd().year());
        ForecastEstimate nasa = comparison.rows().get(0).institutional().stream()
                .filter(value -> "NASA".equals(value.sourceName()))
                .filter(value -> !value.legacy())
                .findFirst().orElseThrow();
        assertEquals("UNDATED", nasa.status());
        assertNull(nasa.year());
        ForecastEstimate precursor = comparison.rows().get(0).institutional().stream()
                .filter(value -> "PRECURSOR".equals(value.status()))
                .findFirst().orElseThrow();
        assertEquals("SPACEX 선행 목표", precursor.label());
    }

    @Test
    void oldCrowdDataMakesTheComparisonStaleAndLegacyRangeRemainsVisible() {
        LocalDate stale = TODAY.minusDays(46);
        when(forecasts.findLatestCrowdObservation("META-LANDING"))
                .thenReturn(Optional.of(observation("META-LANDING", "2044.8", stale)));
        when(forecasts.findLatestCrowdObservation("META-SETTLEMENT"))
                .thenReturn(Optional.of(observation("META-SETTLEMENT", "2090.0", stale)));

        ForecastComparison comparison = service.current();

        assertEquals("STALE", comparison.status());
        ForecastEstimate legacy = comparison.rows().get(0).institutional().stream()
                .filter(ForecastEstimate::legacy).findFirst().orElseThrow();
        assertEquals(new BigDecimal("2030"), legacy.yearLow());
        assertEquals(new BigDecimal("2039"), legacy.yearHigh());
    }

    @Test
    void noReviewedReferencesReturnsHonestInsufficientRows() {
        when(forecasts.findAllReferences()).thenReturn(List.of());

        ForecastComparison comparison = service.current();

        assertEquals("INSUFFICIENT_DATA", comparison.status());
        assertEquals(3, comparison.rows().size());
        assertTrue(comparison.rows().stream()
                .allMatch(row -> row.institutional().isEmpty()));
    }

    @Test
    void crowdLiveStatusRequiresTheTokenGateAsWellAsBothBooleanGates() {
        ForecastComparisonService missingToken = new ForecastComparisonService(
                tracker, transport, forecasts, CLOCK, true, true, false);
        ForecastComparisonService allGates = new ForecastComparisonService(
                tracker, transport, forecasts, CLOCK, true, true, true);

        assertEquals("AUTHORIZATION_REQUIRED", missingToken.current().crowdLiveStatus());
        assertEquals("ENABLED", allGates.current().crowdLiveStatus());
    }

    private static SnapshotRow snapshot(double year) {
        return new SnapshotRow(1, 0, TODAY, 0.5, 0, 0.1, 0.1,
                10, 10, year, year - 10, year + 10, year, "params-v1");
    }

    private static TransportProjection projection(double year) {
        return new TransportProjection(
                1, TODAY, "transport-assumptions-v1", "wright-falcon-v1",
                "PROVISIONAL", "PROVISIONAL", List.of("WEAK_FIT"), 3,
                1.0, -0.2, 0.58, 500, 80.0, 100.0, 60.0,
                new BigDecimal("200"), new BigDecimal("500"), new BigDecimal("100"),
                1000.0, 500.0, 2000.0, year, year - 4, year + 10,
                false, false, false, 2025, 150,
                "ASSUMPTION_SENSITIVITY", "PUBLISHED_PRICE",
                "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD",
                "Declared-assumption scenario; not provider internal cost", "FIT_DECLINING");
    }

    private static ExternalForecastObservation observation(
            String key, String smoothed, LocalDate date) {
        return new ExternalForecastObservation(
                1, key, "CROWD", "METACULUS", "Crowd question",
                new BigDecimal(smoothed), new BigDecimal(smoothed), date,
                "a".repeat(64), "CURRENT", 90);
    }

    private static List<ForecastReference> references() {
        return List.of(
                reference("META-LANDING", "CROWD", "METACULUS", "LANDING",
                        "AWAITING_AUTHORIZATION", "DIRECT", null, null, false),
                reference("META-SETTLEMENT", "CROWD", "METACULUS", "SETTLEMENT",
                        "AWAITING_AUTHORIZATION", "PROXY", null, null, false),
                reference("NASA-LANDING", "INSTITUTIONAL", "NASA", "LANDING",
                        "UNDATED", "DIRECT", null, null, false),
                reference("NASA-LANDING-LEGACY", "INSTITUTIONAL", "NASA", "LANDING",
                        "LEGACY", "DIRECT", new BigDecimal("2030"),
                        new BigDecimal("2039"), true),
                reference("SPACEX-CARGO-PRECURSOR", "INSTITUTIONAL", "SPACEX", "LANDING",
                        "PRECURSOR", "PRECURSOR", new BigDecimal("2028"),
                        new BigDecimal("2028"), false),
                reference("SPACEX-RETURN", "INSTITUTIONAL", "SPACEX", "RETURN",
                        "UNDATED", "REQUIREMENT", null, null, false),
                reference("SPACEX-SETTLEMENT", "INSTITUTIONAL", "SPACEX", "SETTLEMENT",
                        "UNDATED", "DIRECT", null, null, false));
    }

    private static ForecastReference reference(
            String key,
            String type,
            String name,
            String track,
            String status,
            String relation,
            BigDecimal low,
            BigDecimal high,
            boolean legacy) {
        return new ForecastReference(
                key, type, name, track, "Question " + key,
                "Definition " + key, status, null, low, high, relation,
                name.equals("METACULUS")
                        ? "https://www.metaculus.com/questions/3515/"
                        : name.equals("NASA")
                                ? "https://www.nasa.gov/moontomarsarchitecture-strategyandobjectives/"
                                : "https://www.spacex.com/mars",
                "locator", TODAY, "REVIEWED_REFERENCE", "a".repeat(64),
                legacy ? "Historical reviewed context." : "Current reviewed context.");
    }
}
