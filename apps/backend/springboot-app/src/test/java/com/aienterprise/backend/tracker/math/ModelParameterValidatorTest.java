package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ModelParameterValidatorTest {

    private final ModelParameterValidator validator = new ModelParameterValidator();

    @Test
    void acceptsApprovedParamsV2Contract() {
        assertDoesNotThrow(() -> validator.validate(validModel()));
    }

    @Test
    void rejectsInvalidScalarBoundsAndWindowOrdering() {
        Params defaults = Params.defaults();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(model(
                copy(defaults, 0.5, defaults.kShrink(), defaults.windowM(),
                        defaults.windowFixedYears(), defaults.windowMinYears(),
                        defaults.windowMaxYears(), defaults.trlMap(), defaults.maturityMap()),
                uncertainties())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(model(
                copy(defaults, defaults.epsilon(), 0, defaults.windowM(),
                        defaults.windowFixedYears(), defaults.windowMinYears(),
                        defaults.windowMaxYears(), defaults.trlMap(), defaults.maturityMap()),
                uncertainties())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(model(
                copy(defaults, defaults.epsilon(), defaults.kShrink(), defaults.windowM(),
                        3, 4, 15, defaults.trlMap(), defaults.maturityMap()),
                uncertainties())));
    }

    @Test
    void rejectsIncompleteNonMonotoneOrNonUnitLevelMaps() {
        Params defaults = Params.defaults();
        Map<Integer, Double> missing = new LinkedHashMap<>(defaults.trlMap());
        missing.remove(4);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(model(
                copy(defaults, defaults.epsilon(), defaults.kShrink(), defaults.windowM(),
                        10, 4, 15, missing, defaults.maturityMap()), uncertainties())));

        Map<Integer, Double> nonMonotone = new LinkedHashMap<>(defaults.trlMap());
        nonMonotone.put(5, 0.10);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(model(
                copy(defaults, defaults.epsilon(), defaults.kShrink(), defaults.windowM(),
                        10, 4, 15, nonMonotone, defaults.maturityMap()), uncertainties())));

        Map<Integer, Double> noUnitEnd = new LinkedHashMap<>(defaults.maturityMap());
        noUnitEnd.put(9, 0.99);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(model(
                copy(defaults, defaults.epsilon(), defaults.kShrink(), defaults.windowM(),
                        10, 4, 15, defaults.trlMap(), noUnitEnd), uncertainties())));
    }

    @Test
    void rejectsDormancyThatCanIncreaseOrInvalidEtaControls() {
        Params p = Params.defaults();
        Params increasingDormancy = new Params(
                "params-v2", p.epsilon(), p.kShrink(), p.windowM(),
                p.windowFixedYears(), p.windowMinYears(), p.windowMaxYears(),
                0.40, -0.10, 0.85, p.dormancyTriggerYears(), p.defaultDeltaE(),
                p.etaClampMinYears(), p.etaClampMaxYears(),
                p.displayDampingDaysPerDay(), p.dailyCostCapUsd(),
                p.trlMap(), p.maturityMap());
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(model(increasingDormancy, uncertainties())));

        Params invalidClamp = new Params(
                "params-v2", p.epsilon(), p.kShrink(), p.windowM(),
                p.windowFixedYears(), p.windowMinYears(), p.windowMaxYears(),
                p.dormancyStart(), p.dormancyStepPerDecade(), p.dormancyFloor(),
                p.dormancyTriggerYears(), p.defaultDeltaE(), 0, 0, 0,
                p.dailyCostCapUsd(), p.trlMap(), p.maturityMap());
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(model(invalidClamp, uncertainties())));
    }

    @Test
    void rejectsMissingExtraOrMalformedUncertainty() {
        Map<String, ParameterUncertainty> missing = new LinkedHashMap<>(uncertainties());
        missing.remove("delta_scale");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(model(Params.defaults(), missing)));

        Map<String, ParameterUncertainty> extra = new LinkedHashMap<>(uncertainties());
        extra.put("hidden_parameter", new ParameterUncertainty(
                "hidden_parameter", "FIXED", 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(model(Params.defaults(), extra)));

        Map<String, ParameterUncertainty> malformed = new LinkedHashMap<>(uncertainties());
        malformed.put("delta_scale", new ParameterUncertainty(
                "delta_scale", "DISCRETE", 1.25, 1.0, 0.75, null));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(model(Params.defaults(), malformed)));
    }

    static ModelParameters validModel() {
        return model(copy(
                Params.defaults(), Params.defaults().epsilon(), Params.defaults().kShrink(),
                Params.defaults().windowM(), Params.defaults().windowFixedYears(),
                Params.defaults().windowMinYears(), Params.defaults().windowMaxYears(),
                Params.defaults().trlMap(), Params.defaults().maturityMap()),
                uncertainties());
    }

    static Map<String, ParameterUncertainty> uncertainties() {
        Map<String, ParameterUncertainty> values = new LinkedHashMap<>();
        put(values, "mc_samples", "FIXED", 1000, 4000, 10000, null);
        put(values, "trend_covariance_scale", "BOUNDED_NORMAL", .5, 1, 1.5, .15);
        put(values, "node_weight_concentration", "DIRICHLET", 50, 200, 500, null);
        put(values, "mapping_sigma", "FIXED", .005, .015, .05, null);
        put(values, "delta_scale", "DISCRETE", .75, 1, 1.25, null);
        put(values, "k_log_sigma", "FIXED", .10, .25, .50, null);
        put(values, "dormancy_start", "BOUNDED_NORMAL", .80, .85, .90, .02);
        put(values, "dormancy_step_per_decade", "BOUNDED_NORMAL", .10, .15, .20, .02);
        put(values, "dormancy_floor", "BOUNDED_NORMAL", .30, .40, .50, .04);
        return Map.copyOf(values);
    }

    private static void put(
            Map<String, ParameterUncertainty> target,
            String name,
            String distribution,
            double lower,
            double central,
            double upper,
            Double scale) {
        target.put(name, new ParameterUncertainty(
                name, distribution, lower, central, upper, scale));
    }

    private static ModelParameters model(
            Params params, Map<String, ParameterUncertainty> uncertainty) {
        return new ModelParameters(params, uncertainty);
    }

    private static Params copy(
            Params original,
            double epsilon,
            double k,
            int m,
            int fixed,
            int min,
            int max,
            Map<Integer, Double> trl,
            Map<Integer, Double> maturity) {
        return new Params(
                "params-v2", epsilon, k, m, fixed, min, max,
                original.dormancyStart(), original.dormancyStepPerDecade(),
                original.dormancyFloor(), original.dormancyTriggerYears(),
                original.defaultDeltaE(), original.etaClampMinYears(),
                original.etaClampMaxYears(), original.displayDampingDaysPerDay(),
                original.dailyCostCapUsd(), trl, maturity);
    }
}
