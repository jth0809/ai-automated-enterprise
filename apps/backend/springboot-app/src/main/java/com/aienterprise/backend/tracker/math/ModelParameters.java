package com.aienterprise.backend.tracker.math;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ModelParameters(
        Params params,
        Map<String, ParameterUncertainty> uncertainty) {

    public ModelParameters {
        params = Objects.requireNonNull(params, "params");
        uncertainty = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(uncertainty, "uncertainty")));
    }
}
