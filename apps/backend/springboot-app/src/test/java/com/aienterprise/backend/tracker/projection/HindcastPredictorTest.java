package com.aienterprise.backend.tracker.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.graph.EffectiveReadinessEngine;

class HindcastPredictorTest {

    @Test
    void projectsCentralReadinessAndDeterministicP10P90() {
        var nodes = ProjectionTestFixtures.nodes();
        var graph = ProjectionTestFixtures.graph(false);
        var model = ProjectionTestFixtures.model();
        var readiness = new EffectiveReadinessEngine().calculate(
                nodes, graph, model.params(), ProjectionTestFixtures.AS_OF);
        var trends = ProjectionTestFixtures.trends(readiness, Map.of(
                1, .10, 2, .08, 3, .06, 4, .04, 5, .02, 6, .01));
        HindcastPredictor predictor = new HindcastPredictor();

        HindcastPredictor.Result first = predictor.predict(
                42, 1_000, nodes, graph, model, readiness, trends,
                ProjectionTestFixtures.AS_OF,
                ProjectionTestFixtures.AS_OF.plusWeeks(52), .85);
        HindcastPredictor.Result second = predictor.predict(
                42, 1_000, nodes, graph, model, readiness, trends,
                ProjectionTestFixtures.AS_OF,
                ProjectionTestFixtures.AS_OF.plusWeeks(52), .85);

        assertEquals(first, second);
        assertEquals(0, first.invalidSamples());
        assertEquals(6, first.pillars().size());
        first.pillars().values().forEach(value -> {
            assertTrue(value.predictedReadiness() >= 0
                    && value.predictedReadiness() <= 1);
            assertTrue(value.p10() <= value.p90());
        });
        assertTrue(first.pillars().get(1).predictedReadiness()
                > first.pillars().get(1).currentReadiness());
    }

    @Test
    void zeroTrendKeepsCentralReadinessAndEtaCensored() {
        var nodes = ProjectionTestFixtures.nodes();
        var graph = ProjectionTestFixtures.graph(false);
        var model = ProjectionTestFixtures.model();
        var readiness = new EffectiveReadinessEngine().calculate(
                nodes, graph, model.params(), ProjectionTestFixtures.AS_OF);
        var trends = ProjectionTestFixtures.trends(readiness, Map.of(
                1, 0.0, 2, 0.0, 3, 0.0, 4, 0.0, 5, 0.0, 6, 0.0));

        HindcastPredictor.Result result = new HindcastPredictor().predict(
                7, 100, nodes, graph, model, readiness, trends,
                ProjectionTestFixtures.AS_OF,
                ProjectionTestFixtures.AS_OF.plusWeeks(52), .99);

        result.pillars().values().forEach(value -> {
            assertEquals(value.currentReadiness(), value.predictedReadiness(), 1e-12);
            assertEquals(null, value.etaYear());
        });
    }

    @Test
    void failsWhenMoreThanOnePercentOfSamplesAreInvalid() {
        ProjectionSampler invalid = new ProjectionSampler() {
            @Override
            SamplingSession prepare(
                    java.util.List<com.aienterprise.backend.tracker.domain.NodeRow> nodes,
                    com.aienterprise.backend.tracker.graph.CapabilityGraph graph,
                    com.aienterprise.backend.tracker.math.ModelParameters model) {
                return random -> {
                    throw new IllegalArgumentException(
                            "synthetic invalid sample");
                };
            }
        };
        var nodes = ProjectionTestFixtures.nodes();
        var graph = ProjectionTestFixtures.graph(false);
        var model = ProjectionTestFixtures.model();
        var readiness = new EffectiveReadinessEngine().calculate(
                nodes, graph, model.params(), ProjectionTestFixtures.AS_OF);
        var trends = ProjectionTestFixtures.trends(readiness, Map.of(
                1, .1, 2, .1, 3, .1, 4, .1, 5, .1, 6, .1));

        assertThrows(IllegalStateException.class, () ->
                new HindcastPredictor(invalid, new EffectiveReadinessEngine()).predict(
                        9, 100, nodes, graph, model, readiness, trends,
                        ProjectionTestFixtures.AS_OF,
                        ProjectionTestFixtures.AS_OF.plusWeeks(52), .85));
    }
}
