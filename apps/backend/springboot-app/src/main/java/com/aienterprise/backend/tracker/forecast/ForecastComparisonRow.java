package com.aienterprise.backend.tracker.forecast;

import java.util.List;

/** One comparison target with four deliberately non-equivalent estimate groups. */
public record ForecastComparisonRow(
        String trackCode,
        String trackLabel,
        String definition,
        ForecastEstimate model,
        ForecastEstimate transport,
        ForecastEstimate crowd,
        List<ForecastEstimate> institutional) {

    public ForecastComparisonRow {
        institutional = List.copyOf(institutional);
    }
}
