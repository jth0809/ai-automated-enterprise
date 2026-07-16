package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.aienterprise.backend.tracker.transport.TransportEconomicsDatasetValidator;
import com.aienterprise.backend.tracker.transport.TransportEconomicsDatasetValidator.AnnualCountEvidence;
import com.aienterprise.backend.tracker.transport.TransportEconomicsDatasetValidator.ValidatedTransportDataset;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;
import com.aienterprise.backend.tracker.transport.TransportPriceObservation;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Imports the immutable, copyright-safe WP3.3 numeric reference dataset. */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class TransportEconomicsLoader {

    private static final Logger log = LoggerFactory.getLogger(TransportEconomicsLoader.class);

    private final TransportEconomicsRepository transportRepository;
    private final TrackerRepository trackerRepository;
    private final Resource resource;
    private final String datasetVersion;

    @Autowired
    public TransportEconomicsLoader(
            TransportEconomicsRepository transportRepository,
            TrackerRepository trackerRepository,
            @Value("${tracker.transport-economics-resource:tracker/transport-economics-v1.json}")
                    String resourcePath,
            @Value("${tracker.transport-economics-dataset-version:transport-economics-v1}")
                    String datasetVersion) {
        this(transportRepository, trackerRepository,
                new ClassPathResource(resourcePath), datasetVersion);
    }

    TransportEconomicsLoader(
            TransportEconomicsRepository transportRepository,
            TrackerRepository trackerRepository,
            Resource resource,
            String datasetVersion) {
        this.transportRepository = transportRepository;
        this.trackerRepository = trackerRepository;
        this.resource = resource;
        this.datasetVersion = datasetVersion;
    }

    @Transactional
    @SchedulerLock(name = "tracker-transport-economics-import", lockAtMostFor = "PT10M")
    public void loadIfNeeded() {
        byte[] bytes = readResource();
        String hash = sha256(bytes);
        Optional<String> existingHash = transportRepository.findImportSha(datasetVersion);
        if (existingHash.isPresent()) {
            if (!hash.equals(existingHash.get())) {
                throw new IllegalStateException(
                        "Transport economics dataset hash mismatch for " + datasetVersion);
            }
            return;
        }

        ValidatedTransportDataset dataset = new TransportEconomicsDatasetValidator()
                .validateJson(new String(bytes, StandardCharsets.UTF_8));
        if (!datasetVersion.equals(dataset.datasetVersion())) {
            throw new IllegalStateException("Transport economics dataset version mismatch: expected "
                    + datasetVersion + " but found " + dataset.datasetVersion());
        }
        if (!dataset.errors().isEmpty()) {
            throw new IllegalStateException("Invalid transport economics dataset " + datasetVersion
                    + System.lineSeparator()
                    + String.join(System.lineSeparator(), dataset.errors()));
        }

        List<LayerBMetric> mirrors = new ArrayList<>();
        dataset.annualCounts().stream()
                .map(this::annualMetric)
                .forEach(mirrors::add);
        dataset.annualFrontier().values().stream()
                .sorted(Comparator.comparingInt(TransportPriceObservation::observationYear))
                .map(this::frontierMetric)
                .forEach(mirrors::add);
        assertCompatibleLayerBMirrors(mirrors);

        transportRepository.insertAssumption(dataset.assumption());
        for (TransportPriceObservation observation : dataset.observations()) {
            transportRepository.insertObservation(observation);
        }
        for (LayerBMetric mirror : mirrors) {
            trackerRepository.upsertLayerBMetric(mirror);
        }
        transportRepository.recordImport(
                datasetVersion, hash, dataset.observations().size(),
                dataset.annualCounts().size(), dataset.cpi().size());
        log.info("tracker transport economics imported {} prices, {} annual counts from {}",
                dataset.observations().size(), dataset.annualCounts().size(), datasetVersion);
    }

    private void assertCompatibleLayerBMirrors(List<LayerBMetric> mirrors) {
        for (LayerBMetric desired : mirrors) {
            trackerRepository.findLayerBMetric(
                    desired.metricCode(), desired.observedOn()).ifPresent(existing -> {
                        if (!sameLayerBMetric(existing, desired)) {
                            throw new IllegalStateException(
                                    "Conflicting Layer B mirror for " + desired.metricCode()
                                            + " on " + desired.observedOn());
                        }
                    });
        }
    }

    private static boolean sameLayerBMetric(LayerBMetric existing, LayerBMetric desired) {
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

    private LayerBMetric annualMetric(AnnualCountEvidence evidence) {
        return new LayerBMetric(
                0, "ANNUAL_FALCON_FAMILY_LAUNCH_COUNT", 1,
                LocalDate.of(evidence.count().year(), 12, 31),
                BigDecimal.valueOf(evidence.count().launches()), "LAUNCHES", "MEASURED",
                evidence.sourceLabel(), evidence.sourceUrl(), evidence.accessedOn(),
                evidence.contentSha256(), evidence.factSummary());
    }

    private LayerBMetric frontierMetric(TransportPriceObservation observation) {
        return new LayerBMetric(
                0, "LEO_PUBLISHED_PRICE_FRONTIER_REAL_2025", 1,
                LocalDate.of(observation.observationYear(), 12, 31),
                observation.realBasisUsdPerKg(), "USD_PER_KG", "PUBLISHED_PRICE",
                observation.sourceLabel(), observation.sourceUrl(), observation.accessedOn(),
                observation.contentSha256(),
                "Annual published-price lower-bound frontier in constant 2025 USD per kg; "
                        + "not provider internal cost.");
    }

    private byte[] readResource() {
        try {
            return resource.getContentAsByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read transport economics dataset " + datasetVersion, failure);
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
