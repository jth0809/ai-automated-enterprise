package com.aienterprise.backend.tracker.projection;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.math.CompleteTrendModel;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.MomentumService;

@Service
@ConditionalOnProperty(
        prefix = "tracker",
        name = "phase4-projection-enabled",
        havingValue = "true")
public class ProjectionService {

    private final ProjectionRepository repository;
    private final MonteCarloProjector projector;

    @Autowired
    public ProjectionService(ProjectionRepository repository) {
        this(repository, new MonteCarloProjector());
    }

    ProjectionService(
            ProjectionRepository repository,
            MonteCarloProjector projector) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    @Transactional
    public ProjectionRunResult run(State state) {
        Objects.requireNonNull(state, "state");
        ProjectionRepository.DatasetImport dataset = repository
                .latestDatasetImport()
                .orElseThrow(() -> new IllegalStateException(
                        "projection requires an auditable backfill import"));
        if (!dataset.nodeSetVersion().equals(state.graph().nodeSetVersion())) {
            throw new IllegalStateException(
                    "latest backfill node-set does not match the active graph");
        }

        double centralSamples = state.model().uncertainty()
                .get("mc_samples").central();
        if (centralSamples != Math.rint(centralSamples)) {
            throw new IllegalStateException(
                    "approved Monte Carlo sample count is not an integer");
        }
        ProjectionInput input = new ProjectionInput(
                state.asOf(), dataset.datasetSha256(), dataset.nodeSetVersion(),
                state.nodes(), state.graph(), state.model(),
                state.centralReadiness(), state.trends(), state.momentum(),
                (int) centralSamples, state.targetReadiness());
        ProjectionFingerprint.Value fingerprint = ProjectionFingerprint.of(input);
        var existing = repository.findCompletedByInputHash(fingerprint.sha256());
        if (existing.isPresent()) {
            return existing.get().output();
        }

        ProjectionRunResult calculated = projector.project(input);
        if (!fingerprint.sha256().equals(calculated.inputSha256())
                || fingerprint.seed() != calculated.seed()) {
            throw new IllegalStateException(
                    "projector returned a result for another input");
        }
        return repository.saveCompleted(input, calculated).output();
    }

    public record State(
            LocalDate asOf,
            List<NodeRow> nodes,
            CapabilityGraph graph,
            ModelParameters model,
            ReadinessResult centralReadiness,
            CompleteTrendModel.Result trends,
            Map<Integer, MomentumService.Status> momentum,
            double targetReadiness) {

        public State {
            asOf = Objects.requireNonNull(asOf, "asOf");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            graph = Objects.requireNonNull(graph, "graph");
            model = Objects.requireNonNull(model, "model");
            centralReadiness = Objects.requireNonNull(
                    centralReadiness, "centralReadiness");
            trends = Objects.requireNonNull(trends, "trends");
            momentum = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(momentum, "momentum")));
        }
    }
}
