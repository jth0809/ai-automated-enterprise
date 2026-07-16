package com.aienterprise.backend.tracker.math;

import java.util.Objects;

public record ParameterUncertainty(
        String name,
        String distribution,
        double lower,
        double central,
        double upper,
        Double scale) {

    public ParameterUncertainty {
        name = Objects.requireNonNull(name, "name");
        distribution = Objects.requireNonNull(distribution, "distribution");
    }
}
