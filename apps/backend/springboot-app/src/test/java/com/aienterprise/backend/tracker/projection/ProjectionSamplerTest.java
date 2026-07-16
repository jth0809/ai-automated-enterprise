package com.aienterprise.backend.tracker.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityEdgeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.ParameterUncertainty;
import com.aienterprise.backend.tracker.math.Params;

class ProjectionSamplerTest {

    private final ProjectionSampler sampler = new ProjectionSampler();

    @Test
    void sameSeedProducesExactlyEqualSampledInputs() {
        var first = sampler.sample(nodes(), graph(), model(),
                new DeterministicRandom(99L));
        var second = sampler.sample(nodes(), graph(), model(),
                new DeterministicRandom(99L));

        assertEquals(first, second);
    }

    @Test
    void oneThousandSamplesPreserveEveryModelConstraint() {
        DeterministicRandom random = new DeterministicRandom(20260716L);
        ProjectionSampler.SamplingSession session = sampler.prepare(
                nodes(), graph(), model());
        Set<Double> allowedDeltaScales = Set.of(0.75, 1.0, 1.25);
        for (int sampleIndex = 0; sampleIndex < 1_000; sampleIndex++) {
            ProjectionSampler.SampledInputs sampled = session.sample(random);

            Map<Integer, Double> sums = sampled.nodes().stream()
                    .collect(Collectors.groupingBy(
                            NodeRow::pillar,
                            Collectors.summingDouble(NodeRow::weight)));
            assertEquals(Set.of(1, 2), sums.keySet());
            sums.values().forEach(sum -> assertEquals(1.0, sum, 1e-12));
            sampled.nodes().forEach(node -> assertTrue(node.weight() > 0));

            assertMonotoneUnitMap(sampled.params().trlMap());
            assertMonotoneUnitMap(sampled.params().maturityMap());
            assertEquals(1.0, sampled.params().trlMap().get(9));
            assertEquals(1.0, sampled.params().maturityMap().get(9));

            assertTrue(allowedDeltaScales.contains(sampled.deltaScale()));
            sampled.graph().edges().forEach(edge ->
                    assertTrue(edge.deltaE() >= 0 && edge.deltaE() <= 0.5));
            assertEquals(sampled.graph().declaredSha256(),
                    sampled.graph().computedSha256());

            assertTrue(sampled.params().kShrink() > 0);
            assertTrue(sampled.trendCovarianceScale() >= 0.5);
            assertTrue(sampled.trendCovarianceScale() <= 1.5);
            assertTrue(sampled.params().dormancyStart()
                    >= sampled.params().dormancyFloor());
            assertTrue(sampled.params().dormancyStepPerDecade() >= 0);
        }
    }

    @Test
    void preparedSessionReusesOneValidatedGraphPerDeltaScale() {
        ProjectionSampler.SamplingSession session = sampler.prepare(
                nodes(), graph(), model());
        DeterministicRandom random = new DeterministicRandom(77L);
        Map<Double, CapabilityGraph> graphsByScale = new LinkedHashMap<>();

        for (int index = 0; index < 100; index++) {
            ProjectionSampler.SampledInputs sampled = session.sample(random);
            CapabilityGraph prior = graphsByScale.putIfAbsent(
                    sampled.deltaScale(), sampled.graph());
            if (prior != null) {
                assertSame(prior, sampled.graph());
            }
        }

        assertEquals(Set.of(0.75, 1.0, 1.25), graphsByScale.keySet());
    }

    private static void assertMonotoneUnitMap(Map<Integer, Double> values) {
        double previous = 0;
        for (int level = 1; level <= 9; level++) {
            double value = values.get(level);
            assertTrue(value >= 0 && value <= 1);
            assertTrue(value >= previous);
            previous = value;
        }
    }

    private static List<NodeRow> nodes() {
        return List.of(
                node(1, "P1-A", 1, "TRL", 3, 0.4),
                node(2, "P1-B", 1, "EGL", 5, 0.6),
                node(3, "P2-A", 2, "TRL", 4, 0.3),
                node(4, "P2-B", 2, "EGL", 6, 0.7));
    }

    private static NodeRow node(
            long id, String code, int pillar,
            String scale, int level, double weight) {
        return new NodeRow(
                id, code, pillar, code, scale, level,
                "OFFICIAL", "ACTIVE", null, null,
                weight, code.endsWith("B"), "fixture", "nodes-v1.0");
    }

    private static CapabilityGraph graph() {
        List<CapabilityEdgeRow> edges = List.of(
                new CapabilityEdgeRow(
                        "graph-v1.0", "P1-A", "P1-B", 1, 0.15));
        CapabilityGraph draft = new CapabilityGraph(
                "graph-v1.0", "nodes-v1.0", "0".repeat(64),
                edges.size(), edges);
        return new CapabilityGraph(
                draft.version(), draft.nodeSetVersion(), draft.computedSha256(),
                draft.declaredEdgeCount(), draft.edges());
    }

    private static ModelParameters model() {
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
        values.put("mc_samples", uncertainty(
                "mc_samples", "FIXED", 1000, 4000, 10000, null));
        values.put("trend_covariance_scale", uncertainty(
                "trend_covariance_scale", "BOUNDED_NORMAL", .5, 1, 1.5, .15));
        values.put("node_weight_concentration", uncertainty(
                "node_weight_concentration", "DIRICHLET", 50, 200, 500, null));
        values.put("mapping_sigma", uncertainty(
                "mapping_sigma", "FIXED", .005, .015, .05, null));
        values.put("delta_scale", uncertainty(
                "delta_scale", "DISCRETE", .75, 1, 1.25, null));
        values.put("k_log_sigma", uncertainty(
                "k_log_sigma", "FIXED", .1, .25, .5, null));
        values.put("dormancy_start", uncertainty(
                "dormancy_start", "BOUNDED_NORMAL", .8, .85, .9, .02));
        values.put("dormancy_step_per_decade", uncertainty(
                "dormancy_step_per_decade", "BOUNDED_NORMAL", .1, .15, .2, .02));
        values.put("dormancy_floor", uncertainty(
                "dormancy_floor", "BOUNDED_NORMAL", .3, .4, .5, .04));
        return new ModelParameters(params, values);
    }

    private static ParameterUncertainty uncertainty(
            String name, String distribution,
            double lower, double central, double upper, Double scale) {
        return new ParameterUncertainty(
                name, distribution, lower, central, upper, scale);
    }
}
