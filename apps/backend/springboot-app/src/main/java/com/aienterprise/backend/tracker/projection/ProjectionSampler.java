package com.aienterprise.backend.tracker.projection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.CapabilityEdgeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.CapabilityGraphValidator;
import com.aienterprise.backend.tracker.math.ModelParameterValidator;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.ParameterUncertainty;
import com.aienterprise.backend.tracker.math.Params;

public class ProjectionSampler {

    private final ModelParameterValidator parameterValidator =
            new ModelParameterValidator();
    private final CapabilityGraphValidator graphValidator =
            new CapabilityGraphValidator();

    SampledInputs sample(
            List<NodeRow> centralNodes,
            CapabilityGraph centralGraph,
            ModelParameters model,
            DeterministicRandom random) {
        if (centralNodes == null || centralGraph == null
                || model == null || random == null) {
            throw new IllegalArgumentException("projection sampling inputs are required");
        }
        parameterValidator.validate(model);

        double trendCovarianceScale = bounded(
                model, "trend_covariance_scale", random);
        double deltaScale = sampleDeltaScale(
                model.uncertainty().get("delta_scale"), random);
        double mappingSigma = model.uncertainty().get("mapping_sigma").central();
        double concentration = model.uncertainty()
                .get("node_weight_concentration").central();
        double kSigma = model.uncertainty().get("k_log_sigma").central();

        Params central = model.params();
        double sampledK = central.kShrink()
                * Math.exp(kSigma * random.gaussian() - 0.5 * kSigma * kSigma);
        double dormancyStart = bounded(model, "dormancy_start", random);
        double dormancyStep = bounded(
                model, "dormancy_step_per_decade", random);
        double dormancyFloor = bounded(model, "dormancy_floor", random);
        if (dormancyStart < dormancyFloor) {
            throw new IllegalArgumentException("sampled dormancy curve is not ordered");
        }

        Params sampledParams = new Params(
                central.version(), central.epsilon(), sampledK,
                central.windowM(), central.windowFixedYears(),
                central.windowMinYears(), central.windowMaxYears(),
                dormancyStart, dormancyStep, dormancyFloor,
                central.dormancyTriggerYears(),
                clamp(central.defaultDeltaE() * deltaScale, 0, 0.5),
                central.etaClampMinYears(), central.etaClampMaxYears(),
                central.displayDampingDaysPerDay(), central.dailyCostCapUsd(),
                sampleMapping(central.trlMap(), mappingSigma, random),
                sampleMapping(central.maturityMap(), mappingSigma, random));
        parameterValidator.validate(new ModelParameters(
                sampledParams, model.uncertainty()));

        List<NodeRow> sampledNodes = sampleWeights(
                centralNodes, concentration, random);
        CapabilityGraph sampledGraph = sampleGraph(
                centralGraph, deltaScale);
        graphValidator.validate(sampledGraph, sampledNodes);
        return new SampledInputs(
                sampledParams, sampledNodes, sampledGraph,
                trendCovarianceScale, deltaScale);
    }

    private static List<NodeRow> sampleWeights(
            List<NodeRow> centralNodes,
            double concentration,
            DeterministicRandom random) {
        if (!Double.isFinite(concentration) || concentration <= 0) {
            throw new IllegalArgumentException("weight concentration must be positive");
        }
        Map<Integer, List<NodeRow>> byPillar = new TreeMap<>();
        centralNodes.stream()
                .sorted(Comparator.comparingInt(NodeRow::pillar)
                        .thenComparing(NodeRow::code))
                .forEach(node -> byPillar.computeIfAbsent(
                        node.pillar(), ignored -> new ArrayList<>()).add(node));

        List<NodeRow> sampled = new ArrayList<>();
        for (List<NodeRow> pillarNodes : byPillar.values()) {
            double[] draws = new double[pillarNodes.size()];
            double total = 0;
            for (int index = 0; index < pillarNodes.size(); index++) {
                NodeRow node = pillarNodes.get(index);
                if (!Double.isFinite(node.weight()) || node.weight() <= 0) {
                    throw new IllegalArgumentException("central node weights must be positive");
                }
                draws[index] = random.gamma(concentration * node.weight());
                total += draws[index];
            }
            if (!Double.isFinite(total) || total <= 0) {
                throw new IllegalArgumentException("Dirichlet draw is invalid");
            }
            for (int index = 0; index < pillarNodes.size(); index++) {
                NodeRow node = pillarNodes.get(index);
                sampled.add(copyWithWeight(node, draws[index] / total));
            }
        }
        return List.copyOf(sampled);
    }

    private static NodeRow copyWithWeight(NodeRow node, double weight) {
        return new NodeRow(
                node.id(), node.code(), node.pillar(), node.nameKo(),
                node.scaleType(), node.currentLevel(), node.verificationLevel(),
                node.nodeStatus(), node.dormantSince(), node.programEndDate(),
                weight, node.integrationNode(), node.description(),
                node.nodeSetVersion());
    }

    private static Map<Integer, Double> sampleMapping(
            Map<Integer, Double> central,
            double sigma,
            DeterministicRandom random) {
        if (!Double.isFinite(sigma) || sigma < 0) {
            throw new IllegalArgumentException("mapping sigma must be nonnegative");
        }
        Map<Integer, Double> sampled = new LinkedHashMap<>();
        double previous = 0;
        for (int level = 1; level <= 8; level++) {
            double value = clamp(
                    central.get(level) + sigma * random.gaussian(), 0, 1);
            value = Math.max(previous, value);
            sampled.put(level, value);
            previous = value;
        }
        sampled.put(9, 1.0);
        return Collections.unmodifiableMap(sampled);
    }

    private static CapabilityGraph sampleGraph(
            CapabilityGraph central, double deltaScale) {
        List<CapabilityEdgeRow> edges = central.edges().stream()
                .sorted(Comparator.comparing(CapabilityEdgeRow::toCode)
                        .thenComparingInt(CapabilityEdgeRow::orGroup)
                        .thenComparing(CapabilityEdgeRow::fromCode))
                .map(edge -> new CapabilityEdgeRow(
                        central.version(), edge.fromCode(), edge.toCode(),
                        edge.orGroup(), clamp(edge.deltaE() * deltaScale, 0, 0.5)))
                .toList();
        CapabilityGraph draft = new CapabilityGraph(
                central.version(), central.nodeSetVersion(), "0".repeat(64),
                edges.size(), edges);
        return new CapabilityGraph(
                draft.version(), draft.nodeSetVersion(), draft.computedSha256(),
                draft.declaredEdgeCount(), draft.edges());
    }

    private static double bounded(
            ModelParameters model,
            String name,
            DeterministicRandom random) {
        ParameterUncertainty value = model.uncertainty().get(name);
        if (value == null || value.scale() == null
                || !"BOUNDED_NORMAL".equals(value.distribution())) {
            throw new IllegalArgumentException("missing bounded uncertainty: " + name);
        }
        return random.boundedNormal(
                value.central(), value.scale(), value.lower(), value.upper());
    }

    private static double sampleDeltaScale(
            ParameterUncertainty value,
            DeterministicRandom random) {
        if (value == null || !"DISCRETE".equals(value.distribution())) {
            throw new IllegalArgumentException("delta_scale must be discrete");
        }
        int choice = Math.min(2, (int) (random.uniform() * 3.0));
        return switch (choice) {
            case 0 -> value.lower();
            case 1 -> value.central();
            default -> value.upper();
        };
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    public record SampledInputs(
            Params params,
            List<NodeRow> nodes,
            CapabilityGraph graph,
            double trendCovarianceScale,
            double deltaScale) {

        public SampledInputs {
            nodes = List.copyOf(nodes);
        }
    }
}
