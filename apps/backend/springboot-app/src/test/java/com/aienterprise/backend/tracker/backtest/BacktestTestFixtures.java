package com.aienterprise.backend.tracker.backtest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.ParameterUncertainty;
import com.aienterprise.backend.tracker.math.Params;

final class BacktestTestFixtures {

    private BacktestTestFixtures() {
    }

    static BacktestHarness.Input input(LocalDate latestCompletedMonday) {
        CapabilityGraph graph = graph();
        BacktestSchedule.Split schedule = BacktestSchedule.create(
                latestCompletedMonday);
        BacktestFingerprint.Descriptor descriptor =
                new BacktestFingerprint.Descriptor(
                        "a".repeat(64), "nodes-v1.0", "r2.0", "params-v2",
                        graph.version(), graph.declaredSha256(), 1_000, schedule);
        return new BacktestHarness.Input(
                descriptor, nodes(), List.of(), graph, model(), Map.of(), .85);
    }

    static List<NodeRow> nodes() {
        return List.of(
                node(1, "P1-A", 1, "TRL"),
                node(2, "P2-A", 2, "TRL"),
                node(3, "P3-A", 3, "TRL"),
                node(4, "P4-A", 4, "TRL"),
                node(5, "P5-A", 5, "TRL"),
                node(6, "P6-A", 6, "EGL"));
    }

    static CapabilityGraph graph() {
        CapabilityGraph draft = new CapabilityGraph(
                "graph-v1.0", "nodes-v1.0", "0".repeat(64), 0, List.of());
        return new CapabilityGraph(
                draft.version(), draft.nodeSetVersion(), draft.computedSha256(),
                0, List.of());
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
        put(values, "mc_samples", "FIXED", 1000, 1000, 10000, null);
        put(values, "trend_covariance_scale", "BOUNDED_NORMAL", .5, 1, 1.5, .15);
        put(values, "node_weight_concentration", "DIRICHLET", 50, 200, 500, null);
        put(values, "mapping_sigma", "FIXED", .005, .015, .05, null);
        put(values, "delta_scale", "DISCRETE", .75, 1, 1.25, null);
        put(values, "k_log_sigma", "FIXED", .1, .25, .5, null);
        put(values, "dormancy_start", "BOUNDED_NORMAL", .8, .85, .9, .02);
        put(values, "dormancy_step_per_decade", "BOUNDED_NORMAL", .1, .15, .2, .02);
        put(values, "dormancy_floor", "BOUNDED_NORMAL", .3, .4, .5, .04);
        return new ModelParameters(params, values);
    }

    private static NodeRow node(long id, String code, int pillar, String scale) {
        return new NodeRow(
                id, code, pillar, code, scale, 0, null,
                "ACTIVE", null, null, 1.0, false,
                "fixture", "nodes-v1.0");
    }

    private static void put(
            Map<String, ParameterUncertainty> values,
            String name,
            String distribution,
            double lower,
            double central,
            double upper,
            Double scale) {
        values.put(name, new ParameterUncertainty(
                name, distribution, lower, central, upper, scale));
    }
}
