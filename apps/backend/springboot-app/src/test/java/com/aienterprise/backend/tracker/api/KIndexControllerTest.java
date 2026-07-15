package com.aienterprise.backend.tracker.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.kindex.KIndexCalculator;
import com.aienterprise.backend.tracker.kindex.KIndexObservation;
import com.aienterprise.backend.tracker.kindex.KIndexRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class KIndexControllerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private KIndexRepository repository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new KIndexController(repository, CLOCK)).build();
    }

    @Test
    void emptyRepositoryReturnsHonestInsufficientResponse() throws Exception {
        mvc.perform(get("/api/tracker/k-index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.latestYear").doesNotExist())
                .andExpect(jsonPath("$.kValue").doesNotExist())
                .andExpect(jsonPath("$.series").isEmpty())
                .andExpect(jsonPath("$.readiness").doesNotExist())
                .andExpect(jsonPath("$.etaYear").doesNotExist());
    }

    @Test
    void olderLatestObservationIsMarkedStale() throws Exception {
        insert(2023, new BigDecimal("160000"));

        mvc.perform(get("/api/tracker/k-index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.latestYear").value(2023))
                .andExpect(jsonPath("$.annualDelta").doesNotExist())
                .andExpect(jsonPath("$.series.length()").value(1));
    }

    @Test
    void currentResponseCapsSeriesAtEightyAndExcludesEtaSemantics()
            throws Exception {
        for (int index = 0; index < 81; index++) {
            insert(1944 + index,
                    BigDecimal.valueOf(100_000L + index * 1_000L));
        }

        mvc.perform(get("/api/tracker/k-index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CURRENT"))
                .andExpect(jsonPath("$.latestYear").value(2024))
                .andExpect(jsonPath("$.accountingBasis").value("SUBSTITUTION"))
                .andExpect(jsonPath("$.sourceName").value("Reviewed source"))
                .andExpect(jsonPath("$.sourceUrl")
                        .value("https://example.test/energy"))
                .andExpect(jsonPath("$.accessedOn").value("2026-07-15"))
                .andExpect(jsonPath("$.annualDelta").isNumber())
                .andExpect(jsonPath("$.typeOneGap").isNumber())
                .andExpect(jsonPath("$.typeOneMultiplier").isNumber())
                .andExpect(jsonPath("$.series.length()").value(80))
                .andExpect(jsonPath("$.series[0].year").value(1945))
                .andExpect(jsonPath("$.series[79].year").value(2024))
                .andExpect(jsonPath("$.readiness").doesNotExist())
                .andExpect(jsonPath("$.etaYear").doesNotExist())
                .andExpect(jsonPath("$.typeOneEtaYear").doesNotExist());
    }

    private void insert(int year, BigDecimal energy) {
        var calculation = new KIndexCalculator().calculate(energy);
        repository.upsert(new KIndexObservation(
                year, energy.setScale(3), calculation.powerWatts(),
                calculation.kValue(), "SUBSTITUTION", "Reviewed source",
                "https://example.test/energy", LocalDate.of(2026, 7, 15),
                "k-index-test-v1"));
    }
}
