package com.aienterprise.backend.tracker.prediction;

import java.util.List;
import java.util.Objects;

/** Versioned probability-model settings, independent from the ETA model. */
public record HazardParameters(
        String version,
        double kappaNodeYears,
        double probabilityFloor,
        double probabilityCeiling,
        List<Integer> horizonsMonths,
        int cohortLimit,
        int pillarLimit,
        int calibrationMinOutcomes,
        int calibrationMinQuarters) {

    private static final List<Integer> DECLARED_HORIZONS = List.of(6, 12, 18, 24);

    public HazardParameters {
        if (version == null || version.isBlank() || version.length() > 40
                || !Double.isFinite(kappaNodeYears) || kappaNodeYears <= 0
                || !Double.isFinite(probabilityFloor)
                || !Double.isFinite(probabilityCeiling)
                || probabilityFloor < 0
                || probabilityFloor >= probabilityCeiling
                || probabilityCeiling > 1
                || !DECLARED_HORIZONS.equals(horizonsMonths)
                || cohortLimit < 1 || cohortLimit > 12
                || pillarLimit < 1 || pillarLimit > 2
                || calibrationMinOutcomes < 30
                || calibrationMinQuarters < 4) {
            throw new IllegalArgumentException("invalid hazard parameter contract");
        }
        horizonsMonths = List.copyOf(Objects.requireNonNull(
                horizonsMonths, "horizonsMonths"));
    }

    public static HazardParameters defaults() {
        return new HazardParameters(
                "hazard-v1", 4.0, 0.02, 0.98,
                DECLARED_HORIZONS, 12, 2, 30, 4);
    }
}
