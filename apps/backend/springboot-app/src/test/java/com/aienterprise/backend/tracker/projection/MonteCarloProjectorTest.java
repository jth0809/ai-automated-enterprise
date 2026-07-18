package com.aienterprise.backend.tracker.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.EffectiveReadinessEngine;
import com.aienterprise.backend.tracker.math.ModelParameters;

class MonteCarloProjectorTest {

    @Test
    void deterministicSamplesProduceSevenRowsAndOverallMaximum() {
        ProjectionRunResult run = new MonteCarloProjector().project(
                ProjectionTestFixtures.input());

        assertEquals(java.util.Set.of(0, 1, 2, 3, 4, 5, 6),
                run.results().keySet());
        assertEquals(1_000, run.requestedSamples());
        assertEquals(1_000, run.validSamples());
        assertEquals(0, run.invalidSamples());
        double pillarMaximum = run.results().entrySet().stream()
                .filter(entry -> entry.getKey() != 0)
                .map(Map.Entry::getValue)
                .map(ProjectionResult::etaP50)
                .mapToDouble(Double::doubleValue)
                .max().orElseThrow();
        assertEquals(pillarMaximum, run.results().get(0).etaP50(), 1e-12);
        run.results().values().forEach(result -> {
            assertEquals(result.etaP10(), result.etaP50());
            assertEquals(result.etaP50(), result.etaP90());
            assertEquals(0, result.censoredFraction());
        });
    }

    @Test
    void oneCensoredPillarCensorsEveryOverallSample() {
        ProjectionRunResult run = new MonteCarloProjector().project(
                ProjectionTestFixtures.input(Map.of(
                        1, .10, 2, .10, 3, .10, 4, .10, 5, .10, 6, -1.0)));

        ProjectionResult pillarSix = run.results().get(6);
        ProjectionResult overall = run.results().get(0);
        assertNull(pillarSix.etaP10());
        assertNull(pillarSix.etaP50());
        assertNull(pillarSix.etaP90());
        assertEquals(1.0, pillarSix.censoredFraction());
        assertNull(overall.etaP10());
        assertNull(overall.etaP50());
        assertNull(overall.etaP90());
        assertEquals(1.0, overall.censoredFraction());
        for (int pillar = 1; pillar <= 5; pillar++) {
            assertTrue(run.results().get(pillar).etaP50() != null);
        }
    }

    @Test
    void sameInputProducesByteStableSummary() {
        MonteCarloProjector projector = new MonteCarloProjector();
        ProjectionInput input = ProjectionTestFixtures.input();

        ProjectionRunResult first = projector.project(input);
        ProjectionRunResult second = projector.project(input);

        assertEquals(first, second);
        assertEquals(first.canonicalText(), second.canonicalText());
    }

    @Test
    void acceptsOnePercentInvalidSamplesAndReportsDiagnostics() {
        ProjectionSampler sampler = failingSampler(10);
        ProjectionRunResult run = new MonteCarloProjector(
                sampler, new EffectiveReadinessEngine())
                .project(ProjectionTestFixtures.input());

        assertEquals(10, run.invalidSamples());
        assertEquals(990, run.validSamples());
        assertEquals(0.01, run.diagnostics().get("invalid_fraction"));
    }

    @Test
    void failsWhenMoreThanOnePercentOfSamplesAreInvalid() {
        ProjectionSampler sampler = failingSampler(11);

        assertThrows(IllegalStateException.class,
                () -> new MonteCarloProjector(
                        sampler, new EffectiveReadinessEngine())
                        .project(ProjectionTestFixtures.input()));
    }

    private static ProjectionSampler failingSampler(int failures) {
        return new ProjectionSampler() {
            private int calls;

            @Override
            SamplingSession prepare(
                    java.util.List<NodeRow> centralNodes,
                    CapabilityGraph centralGraph,
                    ModelParameters model) {
                SamplingSession delegate = super.prepare(
                        centralNodes, centralGraph, model);
                return random -> {
                    if (calls++ < failures) {
                        throw new IllegalArgumentException(
                                "synthetic invalid sample");
                    }
                    return delegate.sample(random);
                };
            }
        };
    }
}
