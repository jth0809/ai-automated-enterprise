package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.forecast.ForecastReferenceValidator;
import com.aienterprise.backend.tracker.forecast.ForecastRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Imports reviewed target definitions without runtime network access. */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class ForecastReferenceLoader {

    private static final Logger log = LoggerFactory.getLogger(ForecastReferenceLoader.class);

    private final ForecastRepository repository;
    private final Resource resource;
    private final String datasetVersion;
    private final Clock clock;

    @Autowired
    public ForecastReferenceLoader(
            ForecastRepository repository,
            @Value("${tracker.forecast-reference-resource:tracker/forecast-reference-v1.json}")
                    String resourcePath,
            @Value("${tracker.forecast-reference-dataset-version:forecast-reference-v1}")
                    String datasetVersion) {
        this(repository, new ClassPathResource(resourcePath), datasetVersion,
                Clock.systemUTC());
    }

    ForecastReferenceLoader(
            ForecastRepository repository,
            Resource resource,
            String datasetVersion,
            Clock clock) {
        this.repository = repository;
        this.resource = resource;
        this.datasetVersion = datasetVersion;
        this.clock = clock;
    }

    @Transactional
    @SchedulerLock(name = "tracker-forecast-reference-import", lockAtMostFor = "PT10M")
    public void loadIfNeeded() {
        byte[] bytes = readResource();
        String hash = sha256(bytes);
        var existing = repository.findImport(datasetVersion);
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().datasetSha256())) {
                throw new IllegalStateException(
                        "Forecast reference hash mismatch for " + datasetVersion);
            }
            return;
        }

        var dataset = new ForecastReferenceValidator().validate(bytes, clock);
        if (!dataset.errors().isEmpty()) {
            throw new IllegalStateException("Invalid forecast reference " + datasetVersion
                    + System.lineSeparator()
                    + String.join(System.lineSeparator(), dataset.errors()));
        }
        for (var record : dataset.records()) {
            repository.upsertReference(record, datasetVersion);
        }
        repository.recordImport(datasetVersion, hash, dataset.records().size());
        log.info("tracker forecast references imported {} reviewed records from {}",
                dataset.records().size(), datasetVersion);
    }

    private byte[] readResource() {
        try {
            return resource.getContentAsByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read forecast reference " + datasetVersion, failure);
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
