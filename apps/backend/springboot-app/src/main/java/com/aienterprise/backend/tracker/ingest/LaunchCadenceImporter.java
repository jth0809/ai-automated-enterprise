package com.aienterprise.backend.tracker.ingest;

import java.time.LocalDate;
import java.util.List;

import com.aienterprise.backend.tracker.domain.LayerBMetric;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.layerb.LaunchCadenceAggregator;
import com.aienterprise.backend.tracker.layerb.LaunchRecord;

/** Persists the measured annual cadence metrics derived from a complete LL2 result. */
public class LaunchCadenceImporter {

    private final TrackerRepository repository;
    private final LaunchCadenceAggregator aggregator;

    public LaunchCadenceImporter(
            TrackerRepository repository, LaunchCadenceAggregator aggregator) {
        this.repository = repository;
        this.aggregator = aggregator;
    }

    public int importYear(int year, List<LaunchRecord> launches, LocalDate accessedOn) {
        List<LayerBMetric> metrics = aggregator.aggregate(year, launches, accessedOn);
        for (LayerBMetric metric : metrics) {
            repository.upsertLayerBMetric(metric);
        }
        return metrics.size();
    }
}
