package com.aienterprise.backend.tracker.backtest;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.backfill.BackfillDatasetFingerprint;
import com.aienterprise.backend.tracker.backfill.BackfillDatasetValidator;
import com.aienterprise.backend.tracker.backfill.ValidatedBackfill;
import com.aienterprise.backend.tracker.domain.BackfillImportRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.CapabilityGraphRepository;
import com.aienterprise.backend.tracker.math.ModelParameterRepository;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.RegimeBreak;
import com.aienterprise.backend.tracker.math.TrendFeatureRepository;

/** Builds a reviewed, cutoff-local, network-free backtest input. */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class BacktestInputFactory {

    private static final String NODE_SET_VERSION = "nodes-v1.0";
    private static final String RUBRIC_VERSION = "r2.0";
    private static final int SAMPLE_COUNT = 1_000;
    private static final double TARGET_READINESS = .85;

    private final TrackerRepository tracker;
    private final CapabilityGraphRepository graphs;
    private final ModelParameterRepository parameters;
    private final TrendFeatureRepository trends;
    private final Resource candidatesResource;
    private final Resource mappingsResource;
    private final String datasetVersion;
    private final Clock clock;

    @Autowired
    public BacktestInputFactory(
            TrackerRepository tracker,
            CapabilityGraphRepository graphs,
            ModelParameterRepository parameters,
            TrendFeatureRepository trends,
            @Value("${tracker.backfill-candidates-resource:tracker/historical-candidates-v1.jsonl}")
            String candidatesPath,
            @Value("${tracker.backfill-resource:tracker/backfill-v1.json}")
            String mappingsPath,
            @Value("${tracker.backfill-dataset-version:backfill-v1}")
            String datasetVersion) {
        this(tracker, graphs, parameters, trends,
                new ClassPathResource(candidatesPath),
                new ClassPathResource(mappingsPath), datasetVersion,
                Clock.systemUTC());
    }

    BacktestInputFactory(
            TrackerRepository tracker,
            CapabilityGraphRepository graphs,
            ModelParameterRepository parameters,
            TrendFeatureRepository trends,
            Resource candidatesResource,
            Resource mappingsResource,
            String datasetVersion,
            Clock clock) {
        this.tracker = tracker;
        this.graphs = graphs;
        this.parameters = parameters;
        this.trends = trends;
        this.candidatesResource = candidatesResource;
        this.mappingsResource = mappingsResource;
        this.datasetVersion = datasetVersion;
        this.clock = clock;
    }

    public BacktestHarness.Input create() {
        ValidatedBackfill reviewed = new BackfillDatasetValidator(
                datasetVersion.startsWith("backfill-v"))
                .validate(candidatesResource, mappingsResource);
        if (!reviewed.errors().isEmpty()) {
            throw new IllegalStateException(
                    "backtest corpus validation failed\n"
                            + String.join("\n", reviewed.errors()));
        }
        BackfillImportRow imported = tracker.findBackfillImport(datasetVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "backtest requires its audited backfill import"));
        String resourceHash = BackfillDatasetFingerprint.sha256(
                candidatesResource, mappingsResource, datasetVersion,
                NODE_SET_VERSION, RUBRIC_VERSION);
        if (!resourceHash.equals(imported.datasetSha256().trim())) {
            throw new IllegalStateException(
                    "backtest corpus hash does not match its import");
        }
        if (reviewed.claims().size() != imported.recordCount()) {
            throw new IllegalStateException(
                    "backtest corpus claim count does not match its import");
        }
        if (!NODE_SET_VERSION.equals(imported.nodeSetVersion())) {
            throw new IllegalStateException(
                    "backtest corpus node set does not match its import");
        }

        List<NodeRow> nodes = tracker.findAllNodes();
        CapabilityGraph graph = graphs.loadActive();
        ModelParameters model = parameters.loadActive();
        LocalDate asOf = latestCompletedMonday(LocalDate.now(clock));
        Map<Integer, List<RegimeBreak>> regimeBreaks = new LinkedHashMap<>();
        trends.findApprovedBreaks(asOf, model.params().version())
                .forEach(value -> regimeBreaks
                        .computeIfAbsent(value.pillar(), ignored ->
                                new java.util.ArrayList<>())
                        .add(value));
        BacktestSchedule.Split schedule = BacktestSchedule.create(asOf);
        BacktestFingerprint.Descriptor descriptor =
                new BacktestFingerprint.Descriptor(
                        resourceHash, imported.nodeSetVersion(), RUBRIC_VERSION,
                        model.params().version(), graph.version(),
                        graph.declaredSha256(), SAMPLE_COUNT, schedule);
        return new BacktestHarness.Input(
                descriptor, nodes, reviewed.claims(), graph, model,
                regimeBreaks, TARGET_READINESS);
    }

    static LocalDate latestCompletedMonday(LocalDate today) {
        if (today.getDayOfWeek() == DayOfWeek.MONDAY) {
            return today.minusWeeks(1);
        }
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
