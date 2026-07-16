package com.aienterprise.backend.tracker.backtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.aienterprise.backend.tracker.graph.CapabilityEdgeRow;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.Params;

public record BacktestCandidate(
        int windowM,
        double kShrink,
        double deltaScale) implements Comparable<BacktestCandidate> {

    private static final List<Integer> WINDOW_VALUES = List.of(4, 6, 8);
    private static final List<Double> K_VALUES = List.of(2.0, 4.0, 8.0);
    private static final List<Double> DELTA_VALUES = List.of(.75, 1.0, 1.25);

    public static final String REGISTRY_VERSION = "backtest-candidates-v1";
    public static final BacktestCandidate DEFAULT =
            new BacktestCandidate(6, 4, 1.0);

    private static final List<BacktestCandidate> REGISTRY = buildRegistry();

    public BacktestCandidate {
        if (!WINDOW_VALUES.contains(windowM)
                || !containsExactly(K_VALUES, kShrink)
                || !containsExactly(DELTA_VALUES, deltaScale)) {
            throw new IllegalArgumentException(
                    "candidate must belong to " + REGISTRY_VERSION);
        }
    }

    public static List<BacktestCandidate> registry() {
        return REGISTRY;
    }

    public String id() {
        return String.format(Locale.ROOT, "m%d-k%.0f-d%.2f",
                windowM, kShrink, deltaScale);
    }

    public double defaultDistance() {
        return Math.abs(windowM - DEFAULT.windowM) / 2.0
                + Math.abs(Math.log(kShrink / DEFAULT.kShrink) / Math.log(2.0))
                + Math.abs(deltaScale - DEFAULT.deltaScale) / .25;
    }

    public Params apply(Params central) {
        Objects.requireNonNull(central, "central");
        return new Params(
                central.version(), central.epsilon(), kShrink, windowM,
                central.windowFixedYears(), central.windowMinYears(),
                central.windowMaxYears(), central.dormancyStart(),
                central.dormancyStepPerDecade(), central.dormancyFloor(),
                central.dormancyTriggerYears(),
                clamp(central.defaultDeltaE() * deltaScale, 0, .5),
                central.etaClampMinYears(), central.etaClampMaxYears(),
                central.displayDampingDaysPerDay(), central.dailyCostCapUsd(),
                central.trlMap(), central.maturityMap());
    }

    public ModelParameters apply(ModelParameters central) {
        Objects.requireNonNull(central, "central");
        return new ModelParameters(apply(central.params()), central.uncertainty());
    }

    public CapabilityGraph apply(CapabilityGraph central) {
        Objects.requireNonNull(central, "central");
        List<CapabilityEdgeRow> edges = central.edges().stream()
                .map(edge -> new CapabilityEdgeRow(
                        central.version(), edge.fromCode(), edge.toCode(),
                        edge.orGroup(), clamp(edge.deltaE() * deltaScale, 0, .5)))
                .toList();
        CapabilityGraph draft = new CapabilityGraph(
                central.version(), central.nodeSetVersion(), "0".repeat(64),
                edges.size(), edges);
        return new CapabilityGraph(
                draft.version(), draft.nodeSetVersion(), draft.computedSha256(),
                draft.declaredEdgeCount(), draft.edges());
    }

    @Override
    public int compareTo(BacktestCandidate other) {
        int byM = Integer.compare(windowM, other.windowM);
        if (byM != 0) {
            return byM;
        }
        int byK = Double.compare(kShrink, other.kShrink);
        return byK != 0 ? byK : Double.compare(deltaScale, other.deltaScale);
    }

    private static List<BacktestCandidate> buildRegistry() {
        List<BacktestCandidate> values = new ArrayList<>(27);
        for (int m : WINDOW_VALUES) {
            for (double k : K_VALUES) {
                for (double delta : DELTA_VALUES) {
                    values.add(new BacktestCandidate(m, k, delta));
                }
            }
        }
        return List.copyOf(values);
    }

    private static boolean containsExactly(List<Double> allowed, double value) {
        return Double.isFinite(value) && allowed.stream()
                .anyMatch(item -> Double.compare(item, value) == 0);
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
