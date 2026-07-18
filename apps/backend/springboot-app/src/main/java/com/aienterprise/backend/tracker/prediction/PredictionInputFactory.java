package com.aienterprise.backend.tracker.prediction;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.backfill.BackfillClaim;
import com.aienterprise.backend.tracker.backfill.BackfillDatasetFingerprint;
import com.aienterprise.backend.tracker.backfill.BackfillDatasetValidator;
import com.aienterprise.backend.tracker.backfill.ValidatedBackfill;
import com.aienterprise.backend.tracker.domain.BackfillImportRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.math.ModelParameterRepository;

/** Builds the audited, network-free input used for one issuance cohort. */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class PredictionInputFactory {

    private static final String NODE_SET_VERSION = "nodes-v1.0";
    private static final String RUBRIC_VERSION = "r2.0";

    private final TrackerRepository tracker;
    private final ModelParameterRepository modelParameters;
    private final PredictionRepository predictions;
    private final Resource candidatesResource;
    private final Resource mappingsResource;
    private final String datasetVersion;
    private final Clock clock;

    @Autowired
    public PredictionInputFactory(
            TrackerRepository tracker,
            ModelParameterRepository modelParameters,
            PredictionRepository predictions,
            @Value("${tracker.backfill-candidates-resource:tracker/historical-candidates-v1.jsonl}")
            String candidatesPath,
            @Value("${tracker.backfill-resource:tracker/backfill-v1.json}")
            String mappingsPath,
            @Value("${tracker.backfill-dataset-version:backfill-v1}")
            String datasetVersion) {
        this(tracker, modelParameters, predictions,
                new ClassPathResource(candidatesPath),
                new ClassPathResource(mappingsPath), datasetVersion,
                Clock.systemUTC());
    }

    PredictionInputFactory(
            TrackerRepository tracker,
            ModelParameterRepository modelParameters,
            PredictionRepository predictions,
            Resource candidatesResource,
            Resource mappingsResource,
            String datasetVersion,
            Clock clock) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.modelParameters = Objects.requireNonNull(
                modelParameters, "modelParameters");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.candidatesResource = Objects.requireNonNull(
                candidatesResource, "candidatesResource");
        this.mappingsResource = Objects.requireNonNull(
                mappingsResource, "mappingsResource");
        this.datasetVersion = Objects.requireNonNull(
                datasetVersion, "datasetVersion");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Input create() {
        ValidatedBackfill reviewed = new BackfillDatasetValidator(
                datasetVersion.startsWith("backfill-v"))
                .validate(candidatesResource, mappingsResource);
        if (!reviewed.errors().isEmpty()) {
            throw new IllegalStateException(
                    "prediction corpus validation failed\n"
                            + String.join("\n", reviewed.errors()));
        }
        BackfillImportRow imported = tracker.findBackfillImport(datasetVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "prediction issuance requires its audited backfill import"));
        String resourceHash = BackfillDatasetFingerprint.sha256(
                candidatesResource, mappingsResource, datasetVersion,
                NODE_SET_VERSION, RUBRIC_VERSION);
        if (!resourceHash.equals(imported.datasetSha256().trim())) {
            throw new IllegalStateException(
                    "prediction corpus hash does not match its import");
        }
        if (reviewed.claims().size() != imported.recordCount()) {
            throw new IllegalStateException(
                    "prediction corpus claim count does not match its import");
        }
        if (!NODE_SET_VERSION.equals(imported.nodeSetVersion())) {
            throw new IllegalStateException(
                    "prediction corpus node set does not match its import");
        }

        LocalDate issuedOn = LocalDate.now(clock);
        LocalDate asOf = latestCompletedMonday(issuedOn);
        return new Input(
                resourceHash, imported.nodeSetVersion(), RUBRIC_VERSION,
                asOf, issuedOn, tracker.findAllNodes(), reviewed.claims(),
                modelParameters.loadActive().params().dormancyTriggerYears(),
                predictions.loadActiveParameters());
    }

    static LocalDate latestCompletedMonday(LocalDate today) {
        if (today.getDayOfWeek() == DayOfWeek.MONDAY) {
            return today.minusWeeks(1);
        }
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public record Input(
            String datasetSha256,
            String nodeSetVersion,
            String rubricVersion,
            LocalDate asOf,
            LocalDate issuedOn,
            List<NodeRow> nodes,
            List<BackfillClaim> claims,
            int dormancyTriggerYears,
            HazardParameters hazardParameters) {

        public Input {
            if (datasetSha256 == null || !datasetSha256.matches("[0-9a-f]{64}")
                    || nodeSetVersion == null || nodeSetVersion.isBlank()
                    || rubricVersion == null || rubricVersion.isBlank()
                    || asOf == null || issuedOn == null || issuedOn.isBefore(asOf)
                    || dormancyTriggerYears < 1 || dormancyTriggerYears > 100) {
                throw new IllegalArgumentException("invalid prediction issuance input");
            }
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            hazardParameters = Objects.requireNonNull(
                    hazardParameters, "hazardParameters");
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("prediction nodes are required");
            }
        }
    }
}
