package com.aienterprise.backend.tracker.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.ingest.TransportEconomicsLoader;
import com.aienterprise.backend.tracker.transport.TransportCoherenceReport;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;
import com.aienterprise.backend.tracker.transport.TransportProjection;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TransportEconomicsControllerTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 6, 30);

    @Autowired
    private TransportEconomicsController controller;

    @Autowired
    private TransportEconomicsRepository repository;

    @Autowired
    private TransportEconomicsLoader loader;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void missingProjectionAndReportReturnHonestAvailableResponses() throws Exception {
        mvc.perform(get("/api/tracker/transport-economics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.sufficiencyTier").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.observationCount").value(0))
                .andExpect(jsonPath("$.centralEtaYear").doesNotExist())
                .andExpect(jsonPath("$.earliestEtaYear").doesNotExist())
                .andExpect(jsonPath("$.latestEtaYear").doesNotExist())
                .andExpect(jsonPath("$.centralTargetUsdPerKg").value(200))
                .andExpect(jsonPath("$.easyTargetUsdPerKg").value(500))
                .andExpect(jsonPath("$.hardTargetUsdPerKg").value(100))
                .andExpect(jsonPath("$.basis").value("PUBLISHED_PRICE"))
                .andExpect(jsonPath("$.priceMeaning").value(
                        "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD"))
                .andExpect(jsonPath("$.projectionLabel").value(
                        "Declared-assumption scenario; not provider internal cost"))
                .andExpect(jsonPath("$.intervalKind").value("ASSUMPTION_SENSITIVITY"))
                .andExpect(jsonPath("$.coherenceState").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.coherenceAlertActive").value(false));

        mvc.perform(get("/api/tracker/coherence/transport"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.alertActive").value(false))
                .andExpect(jsonPath("$.consecutiveQuarterStreak").value(0))
                .andExpect(jsonPath("$.wideningFactor").value(1.0));
    }

    @Test
    void latestProjectionAndCoherenceExposeExactHonestyMetadata() throws Exception {
        loader.loadIfNeeded();
        repository.saveProjection(projection());
        repository.saveCoherenceReport(divergentReport());

        mvc.perform(get("/api/tracker/transport-economics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-06-30"))
                .andExpect(jsonPath("$.assumptionVersion").value("transport-assumptions-v1"))
                .andExpect(jsonPath("$.modelVersion").value("wright-falcon-v1"))
                .andExpect(jsonPath("$.status").value("PROVISIONAL"))
                .andExpect(jsonPath("$.qualificationFlags[0]").value("WEAK_FIT"))
                .andExpect(jsonPath("$.observationCount").value(3))
                .andExpect(jsonPath("$.beta").value(-0.2))
                .andExpect(jsonPath("$.centralEtaYear").value(2098.4))
                .andExpect(jsonPath("$.latestEtaYear").doesNotExist())
                .andExpect(jsonPath("$.latestBeyondHorizon").value(true))
                .andExpect(jsonPath("$.basis").value("PUBLISHED_PRICE"))
                .andExpect(jsonPath("$.priceMeaning").value(
                        "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD"))
                .andExpect(jsonPath("$.projectionLabel").value(
                        "Declared-assumption scenario; not provider internal cost"))
                .andExpect(jsonPath("$.intervalKind").value("ASSUMPTION_SENSITIVITY"))
                .andExpect(jsonPath("$.centralTargetUsdPerKg").value(200))
                .andExpect(jsonPath("$.easyTargetUsdPerKg").value(500))
                .andExpect(jsonPath("$.hardTargetUsdPerKg").value(100))
                .andExpect(jsonPath("$.coherenceState").value("DIVERGENT"))
                .andExpect(jsonPath("$.coherenceAlertActive").value(true))
                .andExpect(jsonPath("$.coherenceReportPeriod").value("2026-06-30"));

        mvc.perform(get("/api/tracker/coherence/transport"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportPeriodEnd").value("2026-06-30"))
                .andExpect(jsonPath("$.layerBDirection").value("ADVANCING"))
                .andExpect(jsonPath("$.layerCDirection").value("FLAT"))
                .andExpect(jsonPath("$.state").value("DIVERGENT"))
                .andExpect(jsonPath("$.polarity").value("B_AHEAD"))
                .andExpect(jsonPath("$.consecutiveQuarterStreak").value(2))
                .andExpect(jsonPath("$.alertActive").value(true))
                .andExpect(jsonPath("$.wideningFactor").value(1.5));
    }

    private static TransportProjection projection() {
        return new TransportProjection(
                0, PERIOD, "transport-assumptions-v1", "wright-falcon-v1",
                "PROVISIONAL", "PROVISIONAL", List.of("WEAK_FIT"), 3,
                9.1, -0.2, 0.42, 500, 80.0, 100.0, 60.0,
                new BigDecimal("200.0000"), new BigDecimal("500.0000"),
                new BigDecimal("100.0000"), 50_000.0, 15_000.0, 120_000.0,
                2098.4, 2074.2, null, false, false, true,
                2025, 150, "ASSUMPTION_SENSITIVITY", "PUBLISHED_PRICE",
                "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD",
                "Declared-assumption scenario; not provider internal cost",
                "FIT_DECLINING");
    }

    private static TransportCoherenceReport divergentReport() {
        return new TransportCoherenceReport(
                0, PERIOD, PERIOD,
                "ADVANCING", "ADVANCING", "ADVANCING", "FLAT",
                "DIVERGENT", "B_AHEAD", 2, true,
                new BigDecimal("1.50"), PERIOD);
    }
}
