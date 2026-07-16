package com.aienterprise.backend.tracker.projection;

import java.util.Objects;

import com.aienterprise.backend.tracker.math.MomentumService;

public record ProjectionResult(
        int pillar,
        double readiness,
        Double etaP10,
        Double etaP50,
        Double etaP90,
        double censoredFraction,
        MomentumService.Status momentum) {

    public ProjectionResult {
        if (pillar < 0 || pillar > 6
                || !Double.isFinite(readiness) || readiness < 0 || readiness > 1
                || !Double.isFinite(censoredFraction)
                || censoredFraction < 0 || censoredFraction > 1) {
            throw new IllegalArgumentException("invalid projection result");
        }
        requireFiniteOrNull(etaP10, "etaP10");
        requireFiniteOrNull(etaP50, "etaP50");
        requireFiniteOrNull(etaP90, "etaP90");
        if (etaP10 == null && (etaP50 != null || etaP90 != null)
                || etaP50 == null && etaP90 != null
                || etaP10 != null && etaP50 != null && etaP10 > etaP50
                || etaP50 != null && etaP90 != null && etaP50 > etaP90) {
            throw new IllegalArgumentException(
                    "projection quantiles violate censoring order");
        }
        momentum = Objects.requireNonNull(momentum, "momentum");
    }

    private static void requireFiniteOrNull(Double value, String label) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite or null");
        }
    }
}
