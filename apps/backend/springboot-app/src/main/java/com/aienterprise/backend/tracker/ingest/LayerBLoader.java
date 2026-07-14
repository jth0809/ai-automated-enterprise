package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

import com.aienterprise.backend.tracker.domain.LayerBMetric;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.layerb.LayerBDatasetValidator;
import com.aienterprise.backend.tracker.layerb.LayerBDatasetValidator.ValidatedLayerB;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Loads the copyright-safe Layer B measurement seed idempotently. Re-runs are
 * a no-op when the dataset hash already matches the recorded import; a changed
 * hash under the same version fails closed.
 */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class LayerBLoader {

    private static final Logger log = LoggerFactory.getLogger(LayerBLoader.class);

    private final TrackerRepository repository;
    private final Resource resource;
    private final String datasetVersion;

    @Autowired
    public LayerBLoader(
            TrackerRepository repository,
            @Value("${tracker.layer-b-resource:tracker/layer-b-metrics-v1.json}") String path,
            @Value("${tracker.layer-b-dataset-version:layer-b-v1}") String datasetVersion) {
        this(repository, new ClassPathResource(path), datasetVersion);
    }

    LayerBLoader(TrackerRepository repository, Resource resource, String datasetVersion) {
        this.repository = repository;
        this.resource = resource;
        this.datasetVersion = datasetVersion;
    }

    @Transactional
    @SchedulerLock(name = "tracker-layer-b-import", lockAtMostFor = "PT10M")
    public void loadIfNeeded() {
        byte[] bytes;
        try {
            bytes = resource.getContentAsByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Layer B dataset " + datasetVersion, e);
        }
        String sha256 = sha256(bytes);
        Optional<String> existing = repository.findLayerBImportSha(datasetVersion);
        if (existing.isPresent()) {
            if (!sha256.equals(existing.get())) {
                throw new IllegalStateException(
                        "Layer B dataset hash mismatch for " + datasetVersion);
            }
            return;
        }
        ValidatedLayerB validated = new LayerBDatasetValidator()
                .validateJson(new String(bytes, StandardCharsets.UTF_8));
        if (!validated.errors().isEmpty()) {
            throw new IllegalStateException("Invalid Layer B dataset " + datasetVersion
                    + System.lineSeparator()
                    + String.join(System.lineSeparator(), validated.errors()));
        }
        for (LayerBMetric metric : validated.metrics()) {
            repository.upsertLayerBMetric(metric);
        }
        repository.recordLayerBImport(datasetVersion, sha256, validated.metrics().size());
        log.info("tracker Layer B imported {} measured metrics from dataset {}",
                validated.metrics().size(), datasetVersion);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
