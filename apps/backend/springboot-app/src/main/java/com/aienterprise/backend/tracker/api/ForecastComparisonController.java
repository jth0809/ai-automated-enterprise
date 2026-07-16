package com.aienterprise.backend.tracker.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aienterprise.backend.tracker.forecast.ForecastComparison;
import com.aienterprise.backend.tracker.forecast.ForecastComparisonService;

/** Read-only public projection of explicitly non-equivalent forecast sources. */
@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker")
public class ForecastComparisonController {

    private final ForecastComparisonService service;

    public ForecastComparisonController(ForecastComparisonService service) {
        this.service = service;
    }

    @GetMapping("/forecast-comparison")
    public ForecastComparison current() {
        return service.current();
    }
}
