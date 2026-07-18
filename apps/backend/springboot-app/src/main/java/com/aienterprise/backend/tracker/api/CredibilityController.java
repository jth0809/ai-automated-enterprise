package com.aienterprise.backend.tracker.api;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aienterprise.backend.tracker.backtest.BacktestReport;
import com.aienterprise.backend.tracker.backtest.BacktestRepository;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.graph.CapabilityEdgeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.CapabilityReadinessService;
import com.aienterprise.backend.tracker.graph.NodeReadinessResult;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.math.ModelParameterRepository;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.prediction.HazardParameters;
import com.aienterprise.backend.tracker.prediction.PredictionRepository;
import com.aienterprise.backend.tracker.prediction.PredictionScorecard;
import com.aienterprise.backend.tracker.projection.ProjectionRepository;
import com.aienterprise.backend.tracker.projection.ProjectionRunResult;

/** Public read-only evidence contracts for the Phase 4 credibility program. */
@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker")
public class CredibilityController {

    public static final List<String> HONESTY_LABELS = List.of(
            "ETA는 예보가 아니라 현 추세 지속을 가정한 시나리오 투영이며 구간은 모델 내부의 80%다. 모형족 오류와 미지의 구조 단절 확률은 포함하지 않는다.",
            "수송 $ / kg은 실제 원가가 아니라 공개된 가격을 바탕으로 한 추정치다.",
            "관측 사건은 측정값이고 TRL/EGL 사상·가중치·DAG 집계는 구성 지수다.",
            "수송 경제성 임계값은 자연상수가 아니라 공개된 모델 가정이다.");

    private final TrackerRepository tracker;
    private final ModelParameterRepository modelParameters;
    private final CapabilityReadinessService readiness;
    private final ProjectionRepository projections;
    private final BacktestRepository backtests;
    private final PredictionRepository predictions;
    private final Map<String, Boolean> automaticFeatures;
    private final TransportAssumptions transportAssumptions;
    private final Clock clock;

    @Autowired
    public CredibilityController(
            TrackerRepository tracker,
            ModelParameterRepository modelParameters,
            CapabilityReadinessService readiness,
            ProjectionRepository projections,
            BacktestRepository backtests,
            PredictionRepository predictions,
            @Value("${tracker.phase4-projection-enabled:false}")
            boolean projectionEnabled,
            @Value("${tracker.phase4-backtest-enabled:false}")
            boolean backtestEnabled,
            @Value("${tracker.phase4-prediction-issuance-enabled:false}")
            boolean issuanceEnabled,
            @Value("${tracker.phase4-prediction-resolution-enabled:false}")
            boolean resolutionEnabled,
            @Value("${tracker.ll2-enabled:false}") boolean ll2Enabled,
            @Value("${tracker.official-index-enabled:false}")
            boolean officialIndexEnabled,
            @Value("${tracker.metaculus-enabled:false}")
            boolean metaculusEnabled,
            @Value("${tracker.golden-live-enabled:false}")
            boolean goldenLiveEnabled,
            @Value("${tracker.transport-target-usd-per-kg:200}")
            BigDecimal centralTransportTarget,
            @Value("${tracker.transport-target-easy-usd-per-kg:500}")
            BigDecimal easyTransportTarget,
            @Value("${tracker.transport-target-hard-usd-per-kg:100}")
            BigDecimal hardTransportTarget) {
        this(tracker, modelParameters, readiness, projections, backtests,
                predictions, featureMap(
                        projectionEnabled, backtestEnabled, issuanceEnabled,
                        resolutionEnabled, ll2Enabled, officialIndexEnabled,
                        metaculusEnabled, goldenLiveEnabled),
                new TransportAssumptions(
                        centralTransportTarget, easyTransportTarget,
                        hardTransportTarget, "PUBLISHED_PRICE",
                        "ASSUMPTION_SENSITIVITY"), Clock.systemUTC());
    }

    CredibilityController(
            TrackerRepository tracker,
            ModelParameterRepository modelParameters,
            CapabilityReadinessService readiness,
            ProjectionRepository projections,
            BacktestRepository backtests,
            PredictionRepository predictions,
            Map<String, Boolean> automaticFeatures,
            TransportAssumptions transportAssumptions,
            Clock clock) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.modelParameters = Objects.requireNonNull(
                modelParameters, "modelParameters");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.backtests = Objects.requireNonNull(backtests, "backtests");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.automaticFeatures = Map.copyOf(automaticFeatures);
        this.transportAssumptions = Objects.requireNonNull(
                transportAssumptions, "transportAssumptions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping("/methodology")
    public ResponseEntity<MethodologyResponse> methodology() {
        LocalDate asOf = LocalDate.now(clock);
        List<NodeRow> nodes = tracker.findAllNodes();
        CapabilityGraph graph = readiness.loadActiveGraph(nodes);
        ModelParameters model = modelParameters.loadActive();
        HazardParameters hazard = predictions.loadActiveParameters();
        ProjectionRepository.DatasetImport dataset = projections
                .latestDatasetImport().orElse(null);
        return ResponseEntity.ok(new MethodologyResponse(
                "tracker-methodology-v1", asOf, model, hazard,
                new GraphDescriptor(
                        graph.version(), graph.nodeSetVersion(),
                        graph.declaredSha256(), graph.declaredEdgeCount()),
                dataset == null ? null : new DatasetDescriptor(
                        dataset.datasetVersion(), dataset.datasetSha256(),
                        dataset.nodeSetVersion(), dataset.recordCount(),
                        dataset.importedAt()),
                predictions.findCurrentCalibration().orElse(null),
                predictions.operationsStatus(asOf), formulas(),
                HONESTY_LABELS, automaticFeatures, transportAssumptions));
    }

    @GetMapping("/dag")
    public ResponseEntity<DagResponse> dag() {
        LocalDate asOf = LocalDate.now(clock);
        List<NodeRow> nodes = tracker.findAllNodes().stream()
                .sorted(Comparator.comparing(NodeRow::code)).toList();
        ModelParameters model = modelParameters.loadActive();
        CapabilityGraph graph = readiness.loadActiveGraph(nodes);
        ReadinessResult result = readiness.calculate(
                nodes, graph, model.params(), asOf);
        List<DagNode> outputNodes = new ArrayList<>();
        for (NodeRow node : nodes) {
            NodeReadinessResult value = result.nodes().get(node.code());
            if (value == null) {
                throw new IllegalStateException(
                        "validated DAG omitted node " + node.code());
            }
            outputNodes.add(new DagNode(
                    node.code(), node.nameKo(), node.pillar(),
                    value.rawReadiness(), value.effectiveReadiness(),
                    value.dependencyCap(),
                    value.effectiveReadiness() + 1e-12
                            < value.rawReadiness(),
                    value.limitingGroups(), value.limitingDependencies()));
        }
        return ResponseEntity.ok(new DagResponse(
                graph.version(), graph.nodeSetVersion(),
                graph.declaredSha256(), graph.declaredEdgeCount(), asOf,
                graph.edges(), outputNodes));
    }

    @GetMapping("/projections/current")
    public ResponseEntity<ProjectionResponse> projection() {
        return ResponseEntity.ok(projections.findCurrent()
                .map(run -> new ProjectionResponse(
                        RunStatus.COMPLETED, run.id(),
                        run.output().inputSha256(),
                        Long.toString(run.output().seed()),
                        run.output().requestedSamples(),
                        run.output().validSamples(),
                        run.output().invalidSamples(), run.paramsVersion(),
                        run.graphVersion(), run.nodeSetVersion(),
                        run.datasetSha256(), run.startedAt(),
                        run.completedAt(), run.output()))
                .orElseGet(ProjectionResponse::notRun));
    }

    @GetMapping("/backtests/latest")
    public ResponseEntity<BacktestResponse> backtest() {
        return ResponseEntity.ok(backtests.findCurrent()
                .map(run -> new BacktestResponse(
                        RunStatus.COMPLETED, run.id(),
                        run.report().inputSha256(), run.reportSha256(),
                        Long.toString(run.report().seed()),
                        run.startedAt(), run.completedAt(), run.report()))
                .orElseGet(BacktestResponse::notRun));
    }

    @GetMapping("/predictions")
    public ResponseEntity<PredictionsResponse> predictions() {
        List<PredictionRepository.PublishedCohort> cohorts =
                predictions.findPublishedCohorts();
        return ResponseEntity.ok(new PredictionsResponse(
                cohorts.isEmpty() ? PublicationStatus.EMPTY
                        : PublicationStatus.PUBLISHED,
                cohorts));
    }

    @GetMapping("/predictions/scorecard")
    public ResponseEntity<PredictionScorecard> scorecard() {
        return ResponseEntity.ok(PredictionScorecard.from(
                predictions.findScoredPredictions()));
    }

    private static Map<String, Boolean> featureMap(
            boolean projection,
            boolean backtest,
            boolean issuance,
            boolean resolution,
            boolean ll2,
            boolean officialIndex,
            boolean metaculus,
            boolean goldenLive) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put("phase4Projection", projection);
        values.put("phase4Backtest", backtest);
        values.put("predictionIssuance", issuance);
        values.put("predictionResolution", resolution);
        values.put("launchLibraryPolling", ll2);
        values.put("officialIndexPolling", officialIndex);
        values.put("metaculusPolling", metaculus);
        values.put("goldenLiveEvaluation", goldenLive);
        return values;
    }

    private static Map<String, String> formulas() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("effectiveReadiness", "r_eff = min(r_raw, dependency_cap)");
        values.put("dependencyGroup", "D = min_g(max_{i in g}(r_eff_i + delta_e))");
        values.put("trendShrinkage", "beta_tilde = n/(n+k) * beta_p + k/(n+k) * beta_global");
        values.put("etaScenario", "ETA = now + (logit(0.85) - logit(r_now)) / beta_tilde");
        values.put("nodeHazard", "lambda_node = (N_node + kappa * lambda_pillar) / (E_node + kappa)");
        values.put("eventProbability", "P(T) = 1 - exp(-lambda_node * T)");
        values.put("brier", "Brier = (issued_probability - outcome_binary)^2");
        values.put("calibration", "identity until 30 outcomes and 4 resolved quarters; then time-ordered OOS PAVA");
        return values;
    }

    public enum RunStatus {
        NOT_RUN,
        COMPLETED
    }

    public enum PublicationStatus {
        EMPTY,
        PUBLISHED
    }

    public record GraphDescriptor(
            String version,
            String nodeSetVersion,
            String edgeSha256,
            int edgeCount) {
    }

    public record DatasetDescriptor(
            String version,
            String sha256,
            String nodeSetVersion,
            int recordCount,
            Instant importedAt) {
    }

    public record TransportAssumptions(
            BigDecimal centralUsdPerKg,
            BigDecimal easyUsdPerKg,
            BigDecimal hardUsdPerKg,
            String priceBasis,
            String intervalKind) {
    }

    public record MethodologyResponse(
            String methodologyVersion,
            LocalDate asOf,
            ModelParameters modelParameters,
            HazardParameters hazardParameters,
            GraphDescriptor graph,
            DatasetDescriptor dataset,
            PredictionRepository.StoredCalibration currentCalibration,
            PredictionRepository.OperationsStatus predictionOperations,
            Map<String, String> formulas,
            List<String> honestyLabels,
            Map<String, Boolean> automaticFeatures,
            TransportAssumptions transportAssumptions) {

        public MethodologyResponse {
            formulas = Map.copyOf(formulas);
            honestyLabels = List.copyOf(honestyLabels);
            automaticFeatures = Map.copyOf(automaticFeatures);
        }
    }

    public record DagNode(
            String nodeCode,
            String nodeName,
            int pillar,
            double rawReadiness,
            double effectiveReadiness,
            Double dependencyCap,
            boolean capped,
            List<Integer> limitingGroups,
            List<String> limitingDependencies) {

        public DagNode {
            limitingGroups = List.copyOf(limitingGroups);
            limitingDependencies = List.copyOf(limitingDependencies);
        }
    }

    public record DagResponse(
            String graphVersion,
            String nodeSetVersion,
            String edgeSha256,
            int edgeCount,
            LocalDate asOf,
            List<CapabilityEdgeRow> edges,
            List<DagNode> nodes) {

        public DagResponse {
            edges = List.copyOf(edges);
            nodes = List.copyOf(nodes);
        }
    }

    public record ProjectionResponse(
            RunStatus status,
            Long runId,
            String inputSha256,
            String seed,
            Integer requestedSamples,
            Integer validSamples,
            Integer invalidSamples,
            String paramsVersion,
            String graphVersion,
            String nodeSetVersion,
            String datasetSha256,
            Instant startedAt,
            Instant completedAt,
            ProjectionRunResult output) {

        static ProjectionResponse notRun() {
            return new ProjectionResponse(
                    RunStatus.NOT_RUN, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }
    }

    public record BacktestResponse(
            RunStatus status,
            Long runId,
            String inputSha256,
            String reportSha256,
            String seed,
            Instant startedAt,
            Instant completedAt,
            BacktestReport report) {

        static BacktestResponse notRun() {
            return new BacktestResponse(
                    RunStatus.NOT_RUN, null, null, null, null, null, null,
                    null);
        }
    }

    public record PredictionsResponse(
            PublicationStatus status,
            List<PredictionRepository.PublishedCohort> cohorts) {

        public PredictionsResponse {
            cohorts = List.copyOf(cohorts);
        }
    }
}
