package com.aienterprise.backend.tracker.graph;

import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.math.Params;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class CapabilityReadinessService {

    private final CapabilityGraphRepository repository;
    private final CapabilityGraphValidator validator;
    private final EffectiveReadinessEngine engine;

    public CapabilityReadinessService(CapabilityGraphRepository repository) {
        this.repository = repository;
        this.validator = new CapabilityGraphValidator();
        this.engine = new EffectiveReadinessEngine();
    }

    public ReadinessResult calculate(
            List<NodeRow> nodes,
            Params params,
            LocalDate asOf) {
        CapabilityGraph graph = loadActiveGraph(nodes);
        return calculate(nodes, graph, params, asOf);
    }

    public CapabilityGraph loadActiveGraph(List<NodeRow> nodes) {
        CapabilityGraph graph = repository.loadActive();
        validator.validate(graph, nodes);
        return graph;
    }

    public ReadinessResult calculate(
            List<NodeRow> nodes,
            CapabilityGraph graph,
            Params params,
            LocalDate asOf) {
        validator.validate(graph, nodes);
        return engine.calculate(nodes, graph, params, asOf);
    }
}
