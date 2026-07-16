package com.aienterprise.backend.tracker.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aienterprise.backend.tracker.forecast.ForecastComparison;
import com.aienterprise.backend.tracker.forecast.ForecastComparisonRow;
import com.aienterprise.backend.tracker.forecast.ForecastComparisonService;
import com.aienterprise.backend.tracker.forecast.ForecastEstimate;

class ForecastComparisonControllerTest {

    private final ForecastComparisonService service = mock(ForecastComparisonService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new ForecastComparisonController(service)).build();
    }

    @Test
    void exposesThreeTracksAndFourEstimateGroupsWithoutSensitiveOrScoringFields()
            throws Exception {
        ForecastEstimate unavailable = new ForecastEstimate(
                "NOT_APPLICABLE", null, null, null, null, "NONE",
                "적용 범위 밖", "비교 정의가 다릅니다.", null, null, null,
                null, null, false);
        ForecastEstimate crowd = new ForecastEstimate(
                "CURRENT", new BigDecimal("2044.8"), new BigDecimal("2045.0"),
                null, null, "DIRECT", "90일 평균", "승인된 군중 관측",
                "METACULUS", "https://www.metaculus.com/questions/3515/",
                "post:3515", LocalDate.of(2026, 7, 15), null, false);
        ForecastComparisonRow row = new ForecastComparisonRow(
                "LANDING", "첫 유인 화성 착륙", "성공적인 표면 착륙",
                unavailable, unavailable, crowd, List.of(unavailable));
        when(service.current()).thenReturn(new ForecastComparison(
                "PARTIAL", LocalDate.of(2026, 7, 15), 90,
                "AUTHORIZATION_REQUIRED", List.of(row, row, row)));

        mvc.perform(get("/api/tracker/forecast-comparison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.smoothingWindowDays").value(90))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].model").exists())
                .andExpect(jsonPath("$.rows[0].transport").exists())
                .andExpect(jsonPath("$.rows[0].crowd.year").value(2044.8))
                .andExpect(jsonPath("$.rows[0].institutional").isArray())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.rawResponse").doesNotExist())
                .andExpect(jsonPath("$.readiness").doesNotExist())
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.rows[0].crowd.body").doesNotExist())
                .andExpect(jsonPath("$.rows[0].crowd.quote").doesNotExist());
    }
}
