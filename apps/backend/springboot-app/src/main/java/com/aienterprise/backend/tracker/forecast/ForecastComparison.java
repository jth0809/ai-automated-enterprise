package com.aienterprise.backend.tracker.forecast;

import java.time.LocalDate;
import java.util.List;

/** Public forecast-comparison response; it is read-only and cannot affect scoring. */
public record ForecastComparison(
        String status,
        LocalDate asOfDate,
        int smoothingWindowDays,
        String crowdLiveStatus,
        List<ForecastComparisonRow> rows) {

    public ForecastComparison {
        rows = List.copyOf(rows);
    }
}
