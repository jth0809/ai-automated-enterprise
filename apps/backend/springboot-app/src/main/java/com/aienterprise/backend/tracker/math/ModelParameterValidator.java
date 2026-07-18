package com.aienterprise.backend.tracker.math;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ModelParameterValidator {

    private static final double TOLERANCE = 1e-12;
    private static final Set<String> DISTRIBUTIONS = Set.of(
            "FIXED", "DISCRETE", "BOUNDED_NORMAL", "DIRICHLET");
    private static final Set<String> REQUIRED_UNCERTAINTY = Set.of(
            "mc_samples",
            "trend_covariance_scale",
            "node_weight_concentration",
            "mapping_sigma",
            "delta_scale",
            "k_log_sigma",
            "dormancy_start",
            "dormancy_step_per_decade",
            "dormancy_floor");
    private static final Set<Integer> LEVELS = IntStream.rangeClosed(1, 9)
            .boxed()
            .collect(Collectors.toUnmodifiableSet());

    public void validate(ModelParameters model) {
        if (model == null) {
            throw new IllegalArgumentException("model parameters are required");
        }
        validateParams(model.params());
        validateUncertainty(model.uncertainty());
    }

    private static void validateParams(Params params) {
        if (params.version() == null || params.version().isBlank()
                || params.version().length() > 40) {
            throw new IllegalArgumentException("invalid parameter version");
        }
        requireFinite(params.epsilon(), "epsilon");
        if (!(params.epsilon() > 0 && params.epsilon() < 0.5)) {
            throw new IllegalArgumentException("epsilon must be between 0 and 0.5");
        }
        requireFinite(params.kShrink(), "kShrink");
        if (params.kShrink() <= 0 || params.windowM() <= 0) {
            throw new IllegalArgumentException("k and m must be positive");
        }
        if (!(4 <= params.windowMinYears()
                && params.windowMinYears() <= params.windowFixedYears()
                && params.windowFixedYears() <= params.windowMaxYears()
                && params.windowMaxYears() <= 15)) {
            throw new IllegalArgumentException("invalid adaptive window bounds");
        }
        validateMap(params.trlMap(), "trlMap");
        validateMap(params.maturityMap(), "maturityMap");

        requireUnitInterval(params.dormancyStart(), "dormancyStart");
        requireUnitInterval(params.dormancyFloor(), "dormancyFloor");
        requireFinite(params.dormancyStepPerDecade(), "dormancyStepPerDecade");
        if (params.dormancyStepPerDecade() < 0
                || params.dormancyStepPerDecade() > 1
                || params.dormancyStart() < params.dormancyFloor()
                || params.dormancyTriggerYears() <= 0) {
            throw new IllegalArgumentException("dormancy curve must be non-increasing");
        }
        requireFinite(params.defaultDeltaE(), "defaultDeltaE");
        if (params.defaultDeltaE() < 0 || params.defaultDeltaE() > 0.5) {
            throw new IllegalArgumentException("default delta must be within [0, 0.5]");
        }
        if (params.etaClampMinYears() <= 0
                || params.etaClampMaxYears() < params.etaClampMinYears()
                || params.displayDampingDaysPerDay() <= 0) {
            throw new IllegalArgumentException("ETA clamps and damping must be positive");
        }
        requireFinite(params.dailyCostCapUsd(), "dailyCostCapUsd");
        if (params.dailyCostCapUsd() <= 0) {
            throw new IllegalArgumentException("daily cost cap must be positive");
        }
    }

    private static void validateMap(java.util.Map<Integer, Double> values, String label) {
        if (values == null || !values.keySet().equals(LEVELS)) {
            throw new IllegalArgumentException(label + " must contain levels 1 through 9");
        }
        double previous = -1;
        for (int level = 1; level <= 9; level++) {
            double value = values.get(level);
            requireUnitInterval(value, label + "[" + level + "]");
            if (value + TOLERANCE < previous) {
                throw new IllegalArgumentException(label + " must be monotone");
            }
            previous = value;
        }
        if (Math.abs(values.get(9) - 1.0) > TOLERANCE) {
            throw new IllegalArgumentException(label + " level 9 must equal 1.0");
        }
    }

    private static void validateUncertainty(
            java.util.Map<String, ParameterUncertainty> values) {
        if (values == null || !values.keySet().equals(REQUIRED_UNCERTAINTY)) {
            throw new IllegalArgumentException(
                    "uncertainty names must match the params-v2 registry");
        }
        values.forEach((key, value) -> {
            if (value == null || !key.equals(value.name())) {
                throw new IllegalArgumentException("uncertainty key/name mismatch: " + key);
            }
            if (!DISTRIBUTIONS.contains(value.distribution())) {
                throw new IllegalArgumentException("unknown distribution: " + value.distribution());
            }
            requireFinite(value.lower(), key + ".lower");
            requireFinite(value.central(), key + ".central");
            requireFinite(value.upper(), key + ".upper");
            if (value.lower() > value.central() || value.central() > value.upper()) {
                throw new IllegalArgumentException("invalid uncertainty order: " + key);
            }
            if (value.scale() != null) {
                requireFinite(value.scale(), key + ".scale");
                if (value.scale() <= 0) {
                    throw new IllegalArgumentException("uncertainty scale must be positive: " + key);
                }
            }
            if ("BOUNDED_NORMAL".equals(value.distribution()) && value.scale() == null) {
                throw new IllegalArgumentException("bounded normal requires scale: " + key);
            }
        });

        ParameterUncertainty samples = values.get("mc_samples");
        if (samples.lower() < 1000 || samples.upper() > 10000
                || samples.central() != Math.rint(samples.central())) {
            throw new IllegalArgumentException("invalid Monte Carlo sample bounds");
        }
        ParameterUncertainty delta = values.get("delta_scale");
        if (!"DISCRETE".equals(delta.distribution())
                || Math.abs(delta.lower() - 0.75) > TOLERANCE
                || Math.abs(delta.central() - 1.00) > TOLERANCE
                || Math.abs(delta.upper() - 1.25) > TOLERANCE) {
            throw new IllegalArgumentException("delta scale choices must be 0.75/1.00/1.25");
        }
    }

    private static void requireUnitInterval(double value, String label) {
        requireFinite(value, label);
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(label + " must be within [0, 1]");
        }
    }

    private static void requireFinite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}
