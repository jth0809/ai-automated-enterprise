package com.aienterprise.backend.tracker.projection;

import java.util.SplittableRandom;

final class DeterministicRandom {

    private final SplittableRandom random;

    DeterministicRandom(long seed) {
        this.random = new SplittableRandom(seed);
    }

    double uniform() {
        return random.nextDouble();
    }

    double gaussian() {
        double first = Math.max(Double.MIN_NORMAL, 1.0 - uniform());
        double second = uniform();
        return Math.sqrt(-2.0 * Math.log(first))
                * Math.cos(2.0 * Math.PI * second);
    }

    double boundedNormal(
            double center, double scale,
            double lower, double upper) {
        if (!Double.isFinite(center) || !Double.isFinite(scale)
                || !Double.isFinite(lower) || !Double.isFinite(upper)
                || scale <= 0 || lower > center || center > upper) {
            throw new IllegalArgumentException("invalid bounded-normal parameters");
        }
        return Math.max(lower, Math.min(upper, center + scale * gaussian()));
    }

    double gamma(double shape) {
        if (!Double.isFinite(shape) || shape <= 0) {
            throw new IllegalArgumentException("gamma shape must be positive");
        }
        if (shape < 1.0) {
            double draw = gamma(shape + 1.0)
                    * Math.pow(Math.max(Double.MIN_NORMAL, uniform()), 1.0 / shape);
            return Math.max(Double.MIN_NORMAL, draw);
        }

        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double normal = gaussian();
            double candidate = 1.0 + c * normal;
            if (candidate <= 0) {
                continue;
            }
            double cube = candidate * candidate * candidate;
            double uniform = uniform();
            if (uniform < 1.0 - 0.0331 * normal * normal * normal * normal
                    || Math.log(Math.max(Double.MIN_NORMAL, uniform))
                            < 0.5 * normal * normal
                                    + d * (1.0 - cube + Math.log(cube))) {
                return Math.max(Double.MIN_NORMAL, d * cube);
            }
        }
    }
}
