package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
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

import com.aienterprise.backend.tracker.kindex.KIndexCalculator;
import com.aienterprise.backend.tracker.kindex.KIndexCsvValidator;
import com.aienterprise.backend.tracker.kindex.KIndexObservation;
import com.aienterprise.backend.tracker.kindex.KIndexRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Imports reviewed annual energy observations without any runtime egress. */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class KIndexLoader {

    private static final Logger log = LoggerFactory.getLogger(KIndexLoader.class);

    private final KIndexRepository repository;
    private final Resource resource;
    private final String datasetVersion;
    private final Clock clock;

    @Autowired
    public KIndexLoader(
            KIndexRepository repository,
            @Value("${tracker.k-index-resource:tracker/k-index-energy-v1.csv}")
                    String resourcePath,
            @Value("${tracker.k-index-dataset-version:k-index-energy-v1}")
                    String datasetVersion) {
        this(repository, new ClassPathResource(resourcePath), datasetVersion,
                Clock.systemUTC());
    }

    KIndexLoader(
            KIndexRepository repository,
            Resource resource,
            String datasetVersion,
            Clock clock) {
        this.repository = repository;
        this.resource = resource;
        this.datasetVersion = datasetVersion;
        this.clock = clock;
    }

    @Transactional
    @SchedulerLock(name = "tracker-k-index-import", lockAtMostFor = "PT10M")
    public void loadIfNeeded() {
        byte[] bytes = readResource();
        String hash = sha256(bytes);
        Optional<String> existingHash = repository.findImportSha(datasetVersion);
        if (existingHash.isPresent()) {
            if (!hash.equals(existingHash.get())) {
                throw new IllegalStateException(
                        "K-index dataset hash mismatch for " + datasetVersion);
            }
            return;
        }

        var dataset = new KIndexCsvValidator().validate(
                new String(bytes, StandardCharsets.UTF_8), clock);
        if (!dataset.errors().isEmpty()) {
            throw new IllegalStateException("Invalid K-index dataset " + datasetVersion
                    + System.lineSeparator()
                    + String.join(System.lineSeparator(), dataset.errors()));
        }
        if (dataset.metadata() == null) {
            throw new IllegalStateException(
                    "Invalid K-index dataset metadata " + datasetVersion);
        }

        KIndexCalculator calculator = new KIndexCalculator();
        for (var raw : dataset.observations()) {
            var calculated = calculator.calculate(raw.primaryEnergyTwh());
            repository.upsert(new KIndexObservation(
                    raw.year(),
                    raw.primaryEnergyTwh().setScale(3, RoundingMode.HALF_UP),
                    calculated.powerWatts(),
                    calculated.kValue(),
                    dataset.metadata().accountingBasis(),
                    dataset.metadata().sourceName(),
                    dataset.metadata().sourceUrl(),
                    dataset.metadata().accessedOn(),
                    datasetVersion));
        }
        repository.recordImport(
                datasetVersion,
                hash,
                dataset.observations().size(),
                dataset.metadata().sourceUrl(),
                dataset.metadata().accessedOn());
        log.info("tracker K-index imported {} annual observations from {}",
                dataset.observations().size(), datasetVersion);
    }

    private byte[] readResource() {
        try {
            return resource.getContentAsByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read K-index dataset " + datasetVersion, failure);
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
