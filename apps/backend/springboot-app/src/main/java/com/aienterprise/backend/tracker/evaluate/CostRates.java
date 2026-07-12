package com.aienterprise.backend.tracker.evaluate;

public record CostRates(double inputPerMillion, double outputPerMillion, double cachedInputPerMillion) {

    public CostRates {
        if (inputPerMillion < 0 || outputPerMillion < 0 || cachedInputPerMillion < 0) {
            throw new IllegalArgumentException("Token prices cannot be negative");
        }
    }

    public double estimate(int inputTokens, int outputTokens, int cachedTokens) {
        if (inputTokens < 0 || outputTokens < 0 || cachedTokens < 0) {
            throw new IllegalArgumentException("Token counts cannot be negative");
        }
        return (inputTokens * inputPerMillion
                + outputTokens * outputPerMillion
                + cachedTokens * cachedInputPerMillion) / 1_000_000.0;
    }
}
