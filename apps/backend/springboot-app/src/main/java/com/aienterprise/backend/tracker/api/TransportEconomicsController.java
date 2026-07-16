package com.aienterprise.backend.tracker.api;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aienterprise.backend.tracker.transport.TransportCoherenceReport;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;
import com.aienterprise.backend.tracker.transport.TransportProjection;

/** Public, assumption-labelled WP3.3 transport economics projection. */
@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker")
public class TransportEconomicsController {

    static final String ASSUMPTION_VERSION = "transport-assumptions-v1";
    static final String MODEL_VERSION = "wright-falcon-v1";
    static final String INTERVAL_KIND = "ASSUMPTION_SENSITIVITY";
    static final String BASIS = "PUBLISHED_PRICE";
    static final String PRICE_MEANING =
            "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD";
    static final String PROJECTION_LABEL =
            "Declared-assumption scenario; not provider internal cost";

    private static final BigDecimal CENTRAL_TARGET = new BigDecimal("200");
    private static final BigDecimal EASY_TARGET = new BigDecimal("500");
    private static final BigDecimal HARD_TARGET = new BigDecimal("100");
    private static final int PRICE_BASIS_YEAR = 2025;
    private static final int HORIZON_YEARS = 150;

    private final TransportEconomicsRepository repository;

    public TransportEconomicsController(TransportEconomicsRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/transport-economics")
    public ResponseEntity<Map<String, Object>> projection() {
        Optional<TransportProjection> projection = repository.findLatestProjection();
        Optional<TransportCoherenceReport> report = repository.findLatestCoherenceReport();
        Map<String, Object> body = projection
                .map(TransportEconomicsController::projectionBody)
                .orElseGet(TransportEconomicsController::missingProjectionBody);
        body.put("coherenceState", report.map(TransportCoherenceReport::state)
                .orElse("INSUFFICIENT_DATA"));
        body.put("coherenceAlertActive", report
                .map(TransportCoherenceReport::alertActive).orElse(false));
        body.put("coherenceReportPeriod", report
                .map(TransportCoherenceReport::reportPeriodEnd)
                .map(Object::toString).orElse(null));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/coherence/transport")
    public ResponseEntity<Map<String, Object>> coherence() {
        return ResponseEntity.ok(repository.findLatestCoherenceReport()
                .map(TransportEconomicsController::coherenceBody)
                .orElseGet(TransportEconomicsController::missingCoherenceBody));
    }

    private static Map<String, Object> projectionBody(TransportProjection projection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asOfDate", text(projection.asOfDate()));
        body.put("assumptionVersion", projection.assumptionVersion());
        body.put("modelVersion", projection.modelVersion());
        body.put("status", projection.status());
        body.put("sufficiencyTier", projection.sufficiencyTier());
        body.put("qualificationFlags", projection.qualificationFlags());
        body.put("observationCount", projection.observationCount());
        body.put("alpha", projection.alpha());
        body.put("beta", projection.beta());
        body.put("rSquared", projection.rSquared());
        body.put("currentCumulativeLaunches", projection.currentCumulativeLaunches());
        body.put("centralCadence", projection.centralCadence());
        body.put("fastCadence", projection.fastCadence());
        body.put("slowCadence", projection.slowCadence());
        body.put("centralTargetUsdPerKg", projection.centralTargetUsdPerKg());
        body.put("easyTargetUsdPerKg", projection.easyTargetUsdPerKg());
        body.put("hardTargetUsdPerKg", projection.hardTargetUsdPerKg());
        body.put("centralRequiredLaunches", projection.centralRequiredLaunches());
        body.put("easyRequiredLaunches", projection.easyRequiredLaunches());
        body.put("hardRequiredLaunches", projection.hardRequiredLaunches());
        body.put("centralEtaYear", projection.centralEtaYear());
        body.put("earliestEtaYear", projection.earliestEtaYear());
        body.put("latestEtaYear", projection.latestEtaYear());
        body.put("centralBeyondHorizon", projection.centralBeyondHorizon());
        body.put("earliestBeyondHorizon", projection.earliestBeyondHorizon());
        body.put("latestBeyondHorizon", projection.latestBeyondHorizon());
        body.put("priceBasisYear", projection.priceBasisYear());
        body.put("horizonYears", projection.horizonYears());
        body.put("intervalKind", projection.intervalKind());
        body.put("basis", projection.basis());
        body.put("priceMeaning", projection.priceMeaning());
        body.put("projectionLabel", projection.projectionLabel());
        body.put("reasonCode", projection.reasonCode());
        return body;
    }

    private static Map<String, Object> missingProjectionBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asOfDate", null);
        body.put("assumptionVersion", ASSUMPTION_VERSION);
        body.put("modelVersion", MODEL_VERSION);
        body.put("status", "INSUFFICIENT_DATA");
        body.put("sufficiencyTier", "INSUFFICIENT_DATA");
        body.put("qualificationFlags", List.of());
        body.put("observationCount", 0);
        body.put("alpha", null);
        body.put("beta", null);
        body.put("rSquared", null);
        body.put("currentCumulativeLaunches", 0);
        body.put("centralCadence", null);
        body.put("fastCadence", null);
        body.put("slowCadence", null);
        body.put("centralTargetUsdPerKg", CENTRAL_TARGET);
        body.put("easyTargetUsdPerKg", EASY_TARGET);
        body.put("hardTargetUsdPerKg", HARD_TARGET);
        body.put("centralRequiredLaunches", null);
        body.put("easyRequiredLaunches", null);
        body.put("hardRequiredLaunches", null);
        body.put("centralEtaYear", null);
        body.put("earliestEtaYear", null);
        body.put("latestEtaYear", null);
        body.put("centralBeyondHorizon", false);
        body.put("earliestBeyondHorizon", false);
        body.put("latestBeyondHorizon", false);
        body.put("priceBasisYear", PRICE_BASIS_YEAR);
        body.put("horizonYears", HORIZON_YEARS);
        body.put("intervalKind", INTERVAL_KIND);
        body.put("basis", BASIS);
        body.put("priceMeaning", PRICE_MEANING);
        body.put("projectionLabel", PROJECTION_LABEL);
        body.put("reasonCode", "NO_PROJECTION");
        return body;
    }

    static Map<String, Object> coherenceBody(TransportCoherenceReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportPeriodEnd", text(report.reportPeriodEnd()));
        body.put("layerCSnapshotDate", text(report.layerCSnapshotDate()));
        body.put("priceDirection", report.priceDirection());
        body.put("cadenceDirection", report.cadenceDirection());
        body.put("layerBDirection", report.layerBDirection());
        body.put("layerCDirection", report.layerCDirection());
        body.put("state", report.state());
        body.put("polarity", report.polarity());
        body.put("consecutiveQuarterStreak", report.consecutiveQuarterStreak());
        body.put("alertActive", report.alertActive());
        body.put("wideningFactor", report.wideningFactor());
        body.put("firstDivergentPeriod", text(report.firstDivergentPeriod()));
        return body;
    }

    private static Map<String, Object> missingCoherenceBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportPeriodEnd", null);
        body.put("layerCSnapshotDate", null);
        body.put("priceDirection", "INSUFFICIENT_DATA");
        body.put("cadenceDirection", "INSUFFICIENT_DATA");
        body.put("layerBDirection", "INSUFFICIENT_DATA");
        body.put("layerCDirection", "INSUFFICIENT_DATA");
        body.put("state", "INSUFFICIENT_DATA");
        body.put("polarity", "NONE");
        body.put("consecutiveQuarterStreak", 0);
        body.put("alertActive", false);
        body.put("wideningFactor", new BigDecimal("1.00"));
        body.put("firstDivergentPeriod", null);
        return body;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
