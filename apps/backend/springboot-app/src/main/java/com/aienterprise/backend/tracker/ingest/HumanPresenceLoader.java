package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.LayerBMetric;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.layerb.HumanPresenceAggregator;
import com.aienterprise.backend.tracker.layerb.HumanPresenceCsvValidator;
import com.aienterprise.backend.tracker.layerb.HumanPresenceDataset;
import com.aienterprise.backend.tracker.layerb.HumanPresenceYear;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Imports reviewed orbital-human population transitions as completed-year
 * Layer B measurements. It never creates capability events or scoring state.
 */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class HumanPresenceLoader {

    static final String PERSON_DAYS_CODE = "ANNUAL_ORBITAL_HUMAN_PERSON_DAYS";
    static final String MAX_POPULATION_CODE = "MAX_SIMULTANEOUS_HUMANS_IN_ORBIT";

    private static final Logger log = LoggerFactory.getLogger(HumanPresenceLoader.class);

    private final TrackerRepository repository;
    private final Resource resource;
    private final String datasetVersion;

    @Autowired
    public HumanPresenceLoader(
            TrackerRepository repository,
            @Value("${tracker.human-presence-resource:tracker/human-presence-transitions-v1.csv}")
            String resourcePath,
            @Value("${tracker.human-presence-dataset-version:human-presence-v1}")
            String datasetVersion) {
        this(repository, new ClassPathResource(resourcePath), datasetVersion);
    }

    HumanPresenceLoader(
            TrackerRepository repository, Resource resource, String datasetVersion) {
        this.repository = repository;
        this.resource = resource;
        this.datasetVersion = datasetVersion;
    }

    @Transactional
    @SchedulerLock(name = "tracker-human-presence-import", lockAtMostFor = "PT10M")
    public void loadIfNeeded() {
        byte[] bytes = readResource();
        String hash = sha256(bytes);
        Optional<String> existingImport = repository.findLayerBImportSha(datasetVersion);
        if (existingImport.isPresent()) {
            if (!hash.equals(existingImport.get())) {
                throw new IllegalStateException(
                        "Human-presence dataset hash mismatch for " + datasetVersion);
            }
            return;
        }

        var validated = new HumanPresenceCsvValidator().validate(bytes);
        if (!validated.errors().isEmpty()) {
            throw new IllegalStateException("Invalid human-presence dataset " + datasetVersion
                    + System.lineSeparator()
                    + String.join(System.lineSeparator(), validated.errors()));
        }
        HumanPresenceDataset dataset = validated.dataset();
        if (!datasetVersion.equals(dataset.datasetVersion())) {
            throw new IllegalStateException(
                    "Human-presence dataset version mismatch: expected " + datasetVersion
                            + " but found " + dataset.datasetVersion());
        }

        List<LayerBMetric> metrics = generatedMetrics(dataset, hash);
        assertCompatibleNaturalKeys(metrics);
        for (LayerBMetric metric : metrics) {
            repository.upsertLayerBMetric(metric);
        }
        repository.recordLayerBImport(datasetVersion, hash, metrics.size());
        log.info("tracker human presence imported {} completed-year Layer B metrics from {}",
                metrics.size(), datasetVersion);
    }

    private List<LayerBMetric> generatedMetrics(
            HumanPresenceDataset dataset, String snapshotSha256) {
        List<LayerBMetric> metrics = new ArrayList<>();
        for (HumanPresenceYear year : new HumanPresenceAggregator().aggregate(dataset)) {
            LocalDate observedOn = LocalDate.of(year.year(), 12, 31);
            metrics.add(new LayerBMetric(
                    0, PERSON_DAYS_CODE, 2, observedOn, year.personDays(),
                    "PERSON_DAYS", "MEASURED", dataset.sourceLabel(), dataset.sourceUrl(),
                    dataset.accessedOn(), snapshotSha256,
                    "Integrated worldwide orbital population time history for " + year.year()
                            + ": " + year.personDays().toPlainString()
                            + " person-days; excludes suborbital population and has no automatic "
                            + "readiness or ETA effect."));
            metrics.add(new LayerBMetric(
                    0, MAX_POPULATION_CODE, 2, observedOn,
                    BigDecimal.valueOf(year.maxOrbitPopulation()),
                    "PEOPLE", "MEASURED", dataset.sourceLabel(), dataset.sourceUrl(),
                    dataset.accessedOn(), snapshotSha256,
                    "Maximum simultaneous humans in orbit during " + year.year() + ": "
                            + year.maxOrbitPopulation()
                            + "; excludes suborbital population and has no automatic readiness "
                            + "or ETA effect."));
        }
        return List.copyOf(metrics);
    }

    private void assertCompatibleNaturalKeys(List<LayerBMetric> metrics) {
        for (LayerBMetric desired : metrics) {
            repository.findLayerBMetric(desired.metricCode(), desired.observedOn())
                    .ifPresent(existing -> {
                        if (!sameMetric(existing, desired)) {
                            throw new IllegalStateException(
                                    "Human-presence natural-key conflict for "
                                            + desired.metricCode() + " on "
                                            + desired.observedOn());
                        }
                    });
        }
    }

    private static boolean sameMetric(LayerBMetric existing, LayerBMetric desired) {
        return existing.pillar() == desired.pillar()
                && existing.value().compareTo(desired.value()) == 0
                && Objects.equals(existing.unit(), desired.unit())
                && Objects.equals(existing.basis(), desired.basis())
                && Objects.equals(existing.sourceLabel(), desired.sourceLabel())
                && Objects.equals(existing.sourceUrl(), desired.sourceUrl())
                && Objects.equals(existing.accessedOn(), desired.accessedOn())
                && Objects.equals(existing.contentSha256(), desired.contentSha256())
                && Objects.equals(existing.factSummary(), desired.factSummary());
    }

    private byte[] readResource() {
        try {
            return resource.getContentAsByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read human-presence dataset " + datasetVersion, failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
