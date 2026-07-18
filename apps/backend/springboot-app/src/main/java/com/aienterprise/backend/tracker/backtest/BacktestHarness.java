package com.aienterprise.backend.tracker.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aienterprise.backend.tracker.backfill.BackfillClaim;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.CapabilityGraphValidator;
import com.aienterprise.backend.tracker.graph.EffectiveReadinessEngine;
import com.aienterprise.backend.tracker.math.CompleteTrendModel;
import com.aienterprise.backend.tracker.math.LogitEta;
import com.aienterprise.backend.tracker.math.ModelParameterValidator;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.RegimeBreak;
import com.aienterprise.backend.tracker.projection.HindcastPredictor;

public final class BacktestHarness {

    private static final double ADVANCE_TOLERANCE = 1e-6;
    private static final double COVERAGE_TOLERANCE = 1e-12;

    private final EvaluatorFactory evaluatorFactory;
    private final CalibrationSelector selector;

    public BacktestHarness() {
        this(new HistoricalEvaluatorFactory(), null);
    }

    BacktestHarness(
            EvaluatorFactory evaluatorFactory,
            CalibrationSelector selector) {
        this.evaluatorFactory = Objects.requireNonNull(
                evaluatorFactory, "evaluatorFactory");
        this.selector = selector;
    }

    public BacktestReport run(Input input) {
        Objects.requireNonNull(input, "input");
        BacktestFingerprint.Value fingerprint = BacktestFingerprint.of(
                input.descriptor());
        CalibrationSelector activeSelector = selector == null
                ? new CalibrationSelector(input.model().params().epsilon())
                : selector;
        EvaluationSession session = evaluatorFactory.open(input, fingerprint);

        List<CandidateEvaluation> calibration = new ArrayList<>();
        for (BacktestCandidate candidate : BacktestCandidate.registry()) {
            CandidateEvaluation evaluation = session.evaluate(
                    candidate, input.descriptor().schedule().calibration().folds());
            requireCohort(evaluation, BacktestSchedule.Cohort.CALIBRATION);
            calibration.add(evaluation);
        }
        CalibrationSelector.CalibrationPool pool =
                new CalibrationSelector.CalibrationPool(calibration.stream()
                        .map(value -> new CalibrationSelector.CandidateMetrics(
                                value.candidate(), value.metrics()))
                        .toList());
        CalibrationSelector.Selection selected = activeSelector.select(pool);
        BacktestCandidate activeCandidate = BacktestCandidate.active(input.model());

        CandidateEvaluation selectedCalibration = calibration.stream()
                .filter(value -> value.candidate().equals(selected.candidate()))
                .findFirst().orElseThrow();
        CandidateEvaluation holdout = session.evaluate(
                selected.candidate(),
                input.descriptor().schedule().holdout().folds());
        requireCohort(holdout, BacktestSchedule.Cohort.HOLDOUT);
        CandidateEvaluation activeCalibration = calibration.stream()
                .filter(value -> value.candidate().equals(activeCandidate))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "active model is outside the pre-registered candidate set"));
        CandidateEvaluation activeHoldout = activeCandidate.equals(
                selected.candidate())
                        ? holdout
                        : session.evaluate(activeCandidate,
                                input.descriptor().schedule().holdout().folds());
        requireCohort(activeHoldout, BacktestSchedule.Cohort.HOLDOUT);

        List<BacktestReport.FoldResult> folds = new ArrayList<>();
        folds.addAll(selectedCalibration.folds());
        folds.addAll(holdout.folds());
        List<BacktestReport.MetricComparison> metrics = compare(
                selectedCalibration.metrics(), holdout.metrics());
        BacktestMetric.Bundle calibrationPersistence = noChangeMetrics(
                selectedCalibration.folds(), input.model().params().epsilon());
        BacktestMetric.Bundle holdoutPersistence = noChangeMetrics(
                holdout.folds(), input.model().params().epsilon());
        List<BacktestReport.ModelEvaluation> modelEvaluations = List.of(
                modelEvaluation(
                        BacktestReport.ModelRole.SELECTED,
                        selected.candidate(), selectedCalibration.metrics(),
                        holdout.metrics(), EnumSet.allOf(BacktestMetric.Code.class)),
                modelEvaluation(
                        BacktestReport.ModelRole.ACTIVE,
                        activeCandidate, activeCalibration.metrics(),
                        activeHoldout.metrics(), EnumSet.allOf(BacktestMetric.Code.class)),
                modelEvaluation(
                        BacktestReport.ModelRole.PERSISTENCE,
                        null, calibrationPersistence, holdoutPersistence,
                        EnumSet.of(
                                BacktestMetric.Code.READINESS_MAE,
                                BacktestMetric.Code.LOGIT_READINESS_MAE)),
                modelEvaluation(
                        BacktestReport.ModelRole.ALWAYS_NO_CHANGE,
                        null, calibrationPersistence, holdoutPersistence,
                        EnumSet.of(BacktestMetric.Code.DIRECTION_ACCURACY)));
        BacktestSchedule.Split schedule = input.descriptor().schedule();
        LocalDate asOf = schedule.all().stream()
                .map(BacktestSchedule.Fold::target)
                .max(LocalDate::compareTo).orElseThrow();

        return new BacktestReport(
                BacktestReport.REPORT_VERSION,
                fingerprint.sha256(), fingerprint.seed(),
                input.descriptor().datasetSha256(),
                input.descriptor().nodeSetVersion(),
                input.descriptor().rubricVersion(),
                input.descriptor().paramsVersion(),
                input.descriptor().graphVersion(),
                BacktestCandidate.REGISTRY_VERSION,
                asOf,
                schedule.calibration().folds().getFirst().cutoff(),
                BacktestSchedule.CALIBRATION_END,
                BacktestSchedule.HOLDOUT_START,
                asOf,
                BacktestSchedule.HORIZON_WEEKS,
                input.descriptor().sampleCount(),
                schedule.calibration().folds().size(),
                schedule.holdout().folds().size(),
                selected.candidate(), selected.objectiveScore(),
                selected.scores(), folds, metrics, modelEvaluations);
    }

    private static List<BacktestReport.MetricComparison> compare(
            BacktestMetric.Bundle calibration,
            BacktestMetric.Bundle holdout) {
        return compare(
                calibration, holdout,
                EnumSet.allOf(BacktestMetric.Code.class));
    }

    private static List<BacktestReport.MetricComparison> compare(
            BacktestMetric.Bundle calibration,
            BacktestMetric.Bundle holdout,
            Set<BacktestMetric.Code> codes) {
        List<BacktestReport.MetricComparison> result = new ArrayList<>(
                codes.size() * 7);
        for (BacktestMetric.Code code : BacktestMetric.Code.values()) {
            if (!codes.contains(code)) {
                continue;
            }
            for (int pillar = 0; pillar <= 6; pillar++) {
                int currentPillar = pillar;
                BacktestMetric.Value calibrationValue = calibration.find(
                        code, currentPillar)
                        .orElseGet(() -> insufficient(code, currentPillar));
                BacktestMetric.Value holdoutValue = holdout.find(
                        code, currentPillar)
                        .orElseGet(() -> insufficient(code, currentPillar));
                result.add(new BacktestReport.MetricComparison(
                        code, pillar,
                        calibrationValue.value(), holdoutValue.value(),
                        calibrationValue.samples(), holdoutValue.samples(),
                        calibrationValue.status(), holdoutValue.status()));
            }
        }
        return List.copyOf(result);
    }

    private static BacktestReport.ModelEvaluation modelEvaluation(
            BacktestReport.ModelRole role,
            BacktestCandidate candidate,
            BacktestMetric.Bundle calibration,
            BacktestMetric.Bundle holdout,
            Set<BacktestMetric.Code> codes) {
        return new BacktestReport.ModelEvaluation(
                role, candidate, compare(calibration, holdout, codes));
    }

    private static BacktestMetric.Bundle noChangeMetrics(
            List<BacktestReport.FoldResult> source, double epsilon) {
        List<BacktestReport.FoldResult> baseline = source.stream()
                .map(fold -> {
                    double current = fold.currentReadiness();
                    boolean covered = Math.abs(
                            fold.actualReadiness() - current)
                            <= COVERAGE_TOLERANCE;
                    return new BacktestReport.FoldResult(
                            fold.foldIndex(), fold.cohort(), fold.cutoff(),
                            fold.target(), fold.pillar(), current, current,
                            fold.actualReadiness(),
                            LogitEta.logitClipped(current, epsilon),
                            fold.actualLogit(), false, fold.actualAdvance(),
                            current, current, covered, null,
                            BacktestMetric.Status.OK);
                })
                .toList();
        return BacktestMetric.aggregate(baseline);
    }

    private static BacktestMetric.Value insufficient(
            BacktestMetric.Code code, int pillar) {
        return new BacktestMetric.Value(
                code, pillar, null, 0, BacktestMetric.Status.INSUFFICIENT_DATA);
    }

    static SnapshotRow targetSnapshot(
            Map<Integer, List<SnapshotRow>> history,
            int pillar,
            LocalDate target) {
        return Objects.requireNonNull(history, "history")
                .getOrDefault(pillar, List.of()).stream()
                .filter(row -> target.equals(row.snapshotDate()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "candidate truth is missing target " + target
                                + " for pillar " + pillar));
    }

    private static void requireCohort(
            CandidateEvaluation evaluation,
            BacktestSchedule.Cohort expected) {
        if (evaluation.folds().isEmpty()
                || evaluation.folds().stream().anyMatch(
                        fold -> fold.cohort() != expected)) {
            throw new IllegalStateException(
                    "candidate evaluation mixed calibration and holdout data");
        }
    }

    interface EvaluatorFactory {
        EvaluationSession open(
                Input input, BacktestFingerprint.Value fingerprint);
    }

    interface EvaluationSession {
        CandidateEvaluation evaluate(
                BacktestCandidate candidate,
                List<BacktestSchedule.Fold> folds);
    }

    record CandidateEvaluation(
            BacktestCandidate candidate,
            List<BacktestReport.FoldResult> folds,
            BacktestMetric.Bundle metrics) {
        CandidateEvaluation {
            candidate = Objects.requireNonNull(candidate, "candidate");
            folds = List.copyOf(Objects.requireNonNull(folds, "folds"));
            metrics = Objects.requireNonNull(metrics, "metrics");
        }
    }

    public record Input(
            BacktestFingerprint.Descriptor descriptor,
            List<NodeRow> nodes,
            List<BackfillClaim> claims,
            CapabilityGraph graph,
            ModelParameters model,
            Map<Integer, List<RegimeBreak>> regimeBreaks,
            double targetReadiness) {

        public Input {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            graph = Objects.requireNonNull(graph, "graph");
            model = Objects.requireNonNull(model, "model");
            if (nodes.isEmpty()
                    || !nodes.stream().map(NodeRow::pillar)
                            .collect(java.util.stream.Collectors.toSet())
                            .equals(Set.of(1, 2, 3, 4, 5, 6))
                    || !descriptor.nodeSetVersion().equals(graph.nodeSetVersion())
                    || !descriptor.graphVersion().equals(graph.version())
                    || !descriptor.graphSha256().equals(graph.declaredSha256())
                    || !descriptor.paramsVersion().equals(model.params().version())
                    || !Double.isFinite(targetReadiness)
                    || targetReadiness <= 0 || targetReadiness >= 1) {
                throw new IllegalArgumentException("invalid backtest input versions or scope");
            }
            new CapabilityGraphValidator().validate(graph, nodes);
            new ModelParameterValidator().validate(model);
            Map<Integer, List<RegimeBreak>> copied = new LinkedHashMap<>();
            Objects.requireNonNull(regimeBreaks, "regimeBreaks")
                    .forEach((pillar, values) -> {
                        if (pillar < 1 || pillar > 6) {
                            throw new IllegalArgumentException("unknown regime-break pillar");
                        }
                        copied.put(pillar, List.copyOf(values));
                    });
            regimeBreaks = Collections.unmodifiableMap(copied);
        }
    }

    private static final class HistoricalEvaluatorFactory
            implements EvaluatorFactory {
        @Override
        public EvaluationSession open(
                Input input, BacktestFingerprint.Value fingerprint) {
            return new HistoricalEvaluationSession(input, fingerprint);
        }
    }

    private static final class HistoricalEvaluationSession
            implements EvaluationSession {

        private final Input input;
        private final BacktestFingerprint.Value fingerprint;
        private final HistoricalClaimReplay replay;
        private final EffectiveReadinessEngine readinessEngine =
                new EffectiveReadinessEngine();
        private final CompleteTrendModel trendModel = new CompleteTrendModel();
        private final HindcastPredictor predictor = new HindcastPredictor();
        private final Map<LocalDate, HistoricalClaimReplay.Frame> frames =
                new HashMap<>();
        private final Map<Double, CapabilityGraph> graphs = new HashMap<>();
        private final Map<Double, Map<Integer, List<SnapshotRow>>> histories =
                new HashMap<>();
        private final LocalDate maxTarget;

        private HistoricalEvaluationSession(
                Input input, BacktestFingerprint.Value fingerprint) {
            this.input = input;
            this.fingerprint = fingerprint;
            this.replay = new HistoricalClaimReplay(input.nodes(), input.claims());
            this.maxTarget = input.descriptor().schedule().all().stream()
                    .map(BacktestSchedule.Fold::target)
                    .max(LocalDate::compareTo).orElseThrow();
            this.graphs.put(1.0, input.graph());
        }

        @Override
        public CandidateEvaluation evaluate(
                BacktestCandidate candidate,
                List<BacktestSchedule.Fold> folds) {
            if (folds == null || folds.isEmpty()) {
                throw new IllegalArgumentException("candidate folds are required");
            }
            BacktestSchedule.Cohort cohort = folds.getFirst().cohort();
            if (folds.stream().anyMatch(fold -> fold.cohort() != cohort)) {
                throw new IllegalArgumentException("candidate folds mix regimes");
            }
            CapabilityGraph graph = graphs.computeIfAbsent(
                    candidate.deltaScale(), ignored -> candidate.apply(input.graph()));
            ModelParameters model = candidate.apply(input.model());
            Map<Integer, List<SnapshotRow>> history = histories.computeIfAbsent(
                    candidate.deltaScale(), ignored -> replay.weeklyHistory(
                            maxTarget, input.model().params(), graph));

            List<BacktestReport.FoldResult> results = new ArrayList<>();
            for (BacktestSchedule.Fold fold : folds) {
                HistoricalClaimReplay.Frame frame = frames.computeIfAbsent(
                        fold.cutoff(), date -> replay.frame(date, input.model().params()));
                var readiness = readinessEngine.calculate(
                        frame.nodes(), graph, model.params(), fold.cutoff());
                Map<Integer, List<SnapshotRow>> visibleHistory = visibleHistory(
                        history, fold.cutoff());
                CompleteTrendModel.Result trends = trendModel.calculate(
                        readiness.effectivePillarReadiness(), visibleHistory,
                        frame.stateChanges(), breaksAt(fold.cutoff(), model),
                        model.params(), fold.cutoff(), input.targetReadiness());
                HindcastPredictor.Result prediction = predictor.predict(
                        BacktestFingerprint.foldSeed(fingerprint, candidate, fold),
                        input.descriptor().sampleCount(), frame.nodes(), graph, model,
                        readiness, trends, fold.cutoff(), fold.target(),
                        input.targetReadiness());
                for (int pillar = 1; pillar <= 6; pillar++) {
                    HindcastPredictor.PillarPrediction projected =
                            prediction.pillars().get(pillar);
                    SnapshotRow actualRow = targetSnapshot(
                            history, pillar, fold.target());
                    double actual = actualRow.readiness();
                    double predictedLogit = LogitEta.logitClipped(
                            projected.predictedReadiness(), model.params().epsilon());
                    double actualLogit = LogitEta.logitClipped(
                            actual, model.params().epsilon());
                    boolean predictedAdvance = projected.predictedReadiness()
                            > projected.currentReadiness() + ADVANCE_TOLERANCE;
                    boolean actualAdvance = actual
                            > projected.currentReadiness() + ADVANCE_TOLERANCE;
                    boolean covered = actual + COVERAGE_TOLERANCE >= projected.p10()
                            && actual - COVERAGE_TOLERANCE <= projected.p90();
                    results.add(new BacktestReport.FoldResult(
                            fold.index(), fold.cohort(), fold.cutoff(), fold.target(),
                            pillar, projected.currentReadiness(),
                            projected.predictedReadiness(), actual,
                            predictedLogit, actualLogit,
                            predictedAdvance, actualAdvance,
                            projected.p10(), projected.p90(), covered,
                            projected.etaYear(), BacktestMetric.Status.OK));
                }
            }
            return new CandidateEvaluation(
                    candidate, results, BacktestMetric.aggregate(results));
        }

        private Map<Integer, RegimeBreak> breaksAt(
                LocalDate cutoff, ModelParameters model) {
            Map<Integer, RegimeBreak> result = new LinkedHashMap<>();
            input.regimeBreaks().forEach((pillar, values) -> values.stream()
                    .filter(value -> !value.breakDate().isAfter(cutoff))
                    .filter(value -> model.params().version()
                            .equals(value.paramsVersion()))
                    .max(Comparator.comparing(RegimeBreak::breakDate)
                            .thenComparingLong(RegimeBreak::id))
                    .ifPresent(value -> result.put(pillar, value)));
            return result;
        }

        private static Map<Integer, List<SnapshotRow>> visibleHistory(
                Map<Integer, List<SnapshotRow>> history,
                LocalDate cutoff) {
            Map<Integer, List<SnapshotRow>> result = new LinkedHashMap<>();
            for (int pillar = 1; pillar <= 6; pillar++) {
                result.put(pillar, history.get(pillar).stream()
                        .takeWhile(row -> !row.snapshotDate().isAfter(cutoff))
                        .toList());
            }
            return result;
        }
    }
}
