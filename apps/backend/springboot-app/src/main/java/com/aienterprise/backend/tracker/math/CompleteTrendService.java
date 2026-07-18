package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@Service
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class CompleteTrendService {

    private final TrackerRepository repository;
    private final TrendFeatureRepository featureRepository;
    private final CompleteTrendModel model;

    @Autowired
    public CompleteTrendService(
            TrackerRepository repository,
            TrendFeatureRepository featureRepository) {
        this(repository, featureRepository, new CompleteTrendModel());
    }

    CompleteTrendService(
            TrackerRepository repository,
            TrendFeatureRepository featureRepository,
            CompleteTrendModel model) {
        this.repository = repository;
        this.featureRepository = featureRepository;
        this.model = model;
    }

    public CompleteTrendModel.Result calculate(
            Map<Integer, Double> readiness,
            Params params,
            LocalDate asOf,
            double targetReadiness) {
        Map<Integer, List<SnapshotRow>> histories = new LinkedHashMap<>();
        Map<Integer, List<StateChangeEvent>> changes = new LinkedHashMap<>();
        Map<Integer, RegimeBreak> breaks = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            int currentPillar = pillar;
            histories.put(currentPillar, repository.findPillarSnapshots(currentPillar));
            changes.put(currentPillar,
                    featureRepository.findStateChanges(currentPillar, asOf));
            featureRepository.findLatestApprovedBreak(
                    currentPillar, asOf, params.version())
                    .ifPresent(value -> breaks.put(currentPillar, value));
        }
        return model.calculate(
                readiness, histories, changes, breaks,
                params, asOf, targetReadiness);
    }
}
