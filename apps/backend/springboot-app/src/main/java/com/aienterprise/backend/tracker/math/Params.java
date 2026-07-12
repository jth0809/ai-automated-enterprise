package com.aienterprise.backend.tracker.math;

import java.util.Map;

public record Params(
        String version,
        double epsilon,
        double kShrink,
        int windowM,
        int windowFixedYears,
        int windowMinYears,
        int windowMaxYears,
        double dormancyStart,
        double dormancyStepPerDecade,
        double dormancyFloor,
        int dormancyTriggerYears,
        double defaultDeltaE,
        int etaClampMinYears,
        int etaClampMaxYears,
        int displayDampingDaysPerDay,
        double dailyCostCapUsd,
        Map<Integer, Double> trlMap,
        Map<Integer, Double> maturityMap) {

    public Params {
        trlMap = Map.copyOf(trlMap);
        maturityMap = Map.copyOf(maturityMap);
    }

    public static Params defaults() {
        Map<Integer, Double> maturity = Map.of(
                1, 0.03,
                2, 0.07,
                3, 0.12,
                4, 0.20,
                5, 0.30,
                6, 0.45,
                7, 0.65,
                8, 0.85,
                9, 1.00);
        return new Params(
                "params-v1", 0.01, 4.0, 6, 10, 4, 15,
                0.85, 0.15, 0.40, 15, 0.15,
                2, 150, 90, 20.0, maturity, maturity);
    }
}
