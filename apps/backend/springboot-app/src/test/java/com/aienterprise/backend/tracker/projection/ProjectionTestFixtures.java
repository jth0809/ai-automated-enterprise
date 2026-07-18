package com.aienterprise.backend.tracker.projection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityEdgeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.EffectiveReadinessEngine;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.math.CompleteTrendModel;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.MomentumService;
import com.aienterprise.backend.tracker.math.ParameterUncertainty;
import com.aienterprise.backend.tracker.math.Params;
import com.aienterprise.backend.tracker.math.PillarTrendResult;

final class ProjectionTestFixtures {

    static final LocalDate AS_OF = LocalDate.of(2026, 7, 16);

    private ProjectionTestFixtures() {
    }

    static ProjectionInput input() {
        return input(Map.of(
                1, 0.10,
                2, 0.10,
                3, 0.10,
                4, 0.10,
                5, 0.10,
                6, 0.10));
    }

    static ProjectionInput input(Map<Integer, Double> slopes) {
        List<NodeRow> nodes = nodes();
        CapabilityGraph graph = graph(false);
        ModelParameters model = model();
        ReadinessResult readiness = new EffectiveReadinessEngine().calculate(
                nodes, graph, model.params(), AS_OF);
        return new ProjectionInput(
                AS_OF,
                "a".repeat(64),
                "nodes-v1.0",
                nodes,
                graph,
                model,
                readiness,
                trends(readiness, slopes),
                momentum(false),
                1_000,
                0.85);
    }

    static List<NodeRow> nodes() {
        return List.of(
                node(1, "P1-A", 1, "TRL", 4),
                node(2, "P2-A", 2, "EGL", 5),
                node(3, "P3-A", 3, "TRL", 6),
                node(4, "P4-A", 4, "EGL", 7),
                node(5, "P5-A", 5, "TRL", 8),
                node(6, "P6-A", 6, "EGL", 3));
    }

    static CapabilityGraph graph(boolean reverseEdges) {
        List<CapabilityEdgeRow> edges = new ArrayList<>(List.of(
                new CapabilityEdgeRow(
                        "graph-v1.0", "P1-A", "P2-A", 1, 0.15),
                new CapabilityEdgeRow(
                        "graph-v1.0", "P3-A", "P4-A", 1, 0.50)));
        if (reverseEdges) {
            Collections.reverse(edges);
        }
        CapabilityGraph draft = new CapabilityGraph(
                "graph-v1.0", "nodes-v1.0", "0".repeat(64),
                edges.size(), edges);
        return new CapabilityGraph(
                draft.version(), draft.nodeSetVersion(), draft.computedSha256(),
                draft.declaredEdgeCount(), draft.edges());
    }

    static ModelParameters model() {
        Params defaults = Params.defaults();
        Params params = new Params(
                "params-v2", defaults.epsilon(), defaults.kShrink(),
                defaults.windowM(), defaults.windowFixedYears(),
                defaults.windowMinYears(), defaults.windowMaxYears(),
                defaults.dormancyStart(), defaults.dormancyStepPerDecade(),
                defaults.dormancyFloor(), defaults.dormancyTriggerYears(),
                defaults.defaultDeltaE(), defaults.etaClampMinYears(),
                defaults.etaClampMaxYears(), defaults.displayDampingDaysPerDay(),
                defaults.dailyCostCapUsd(), defaults.trlMap(), defaults.maturityMap());
        Map<String, ParameterUncertainty> values = new LinkedHashMap<>();
        put(values, "mc_samples", "FIXED", 1_000, 1_000, 1_000, null);
        put(values, "trend_covariance_scale", "BOUNDED_NORMAL", .5, 1, 1.5, .01);
        put(values, "node_weight_concentration", "DIRICHLET", 50, 200, 500, null);
        put(values, "mapping_sigma", "FIXED", 0, 0, 0, null);
        put(values, "delta_scale", "DISCRETE", .75, 1, 1.25, null);
        put(values, "k_log_sigma", "FIXED", 0, 0, 0, null);
        put(values, "dormancy_start", "BOUNDED_NORMAL", .8, .85, .9, .01);
        put(values, "dormancy_step_per_decade", "BOUNDED_NORMAL", .1, .15, .2, .01);
        put(values, "dormancy_floor", "BOUNDED_NORMAL", .3, .4, .5, .01);
        return new ModelParameters(params, values);
    }

    static Map<Integer, MomentumService.Status> momentum(boolean reverse) {
        Map<Integer, MomentumService.Status> values = new LinkedHashMap<>();
        for (int index = 0; index <= 6; index++) {
            int pillar = reverse ? 6 - index : index;
            values.put(pillar, pillar == 0
                    ? MomentumService.Status.INSUFFICIENT_DATA
                    : MomentumService.Status.STEADY);
        }
        return values;
    }

    static CompleteTrendModel.Result trends(
            ReadinessResult readiness,
            Map<Integer, Double> slopes) {
        Map<Integer, PillarTrendResult> pillars = new LinkedHashMap<>();
        double prior = slopes.values().stream().mapToDouble(Double::doubleValue)
                .average().orElseThrow();
        for (int pillar = 1; pillar <= 6; pillar++) {
            double slope = slopes.get(pillar);
            pillars.put(pillar, new PillarTrendResult(
                    pillar,
                    readiness.effectivePillarReadiness().get(pillar),
                    slope,
                    prior,
                    slope,
                    1_000,
                    10,
                    1.0,
                    null,
                    AS_OF.minusYears(10),
                    null,
                    null,
                    null,
                    0,
                    0,
                    Map.of(),
                    11));
        }
        return new CompleteTrendModel.Result(prior, pillars);
    }

    private static NodeRow node(
            long id, String code, int pillar, String scale, int level) {
        return new NodeRow(
                id, code, pillar, code, scale, level,
                "OFFICIAL", "ACTIVE", null, null,
                1.0, false, "fixture", "nodes-v1.0");
    }

    private static void put(
            Map<String, ParameterUncertainty> values,
            String name, String distribution,
            double lower, double central, double upper, Double scale) {
        values.put(name, new ParameterUncertainty(
                name, distribution, lower, central, upper, scale));
    }
}
