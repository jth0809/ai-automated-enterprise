package com.aienterprise.backend.tracker.projection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record ProjectionRunResult(
        String inputSha256,
        long seed,
        int requestedSamples,
        int validSamples,
        int invalidSamples,
        Map<String, Double> diagnostics,
        Map<Integer, ProjectionResult> results) {

    private static final Set<Integer> RESULT_ROWS = Set.of(0, 1, 2, 3, 4, 5, 6);

    public ProjectionRunResult {
        if (inputSha256 == null || !inputSha256.matches("[0-9a-f]{64}")
                || seed < 0 || requestedSamples < 1_000 || requestedSamples > 10_000
                || validSamples <= 0 || invalidSamples < 0
                || validSamples + invalidSamples != requestedSamples) {
            throw new IllegalArgumentException("invalid projection run result");
        }
        diagnostics = immutableDiagnostics(diagnostics);
        results = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(results, "results")));
        if (!results.keySet().equals(RESULT_ROWS)) {
            throw new IllegalArgumentException(
                    "projection run must contain exactly rows 0 through 6");
        }
        results.forEach((pillar, result) -> {
            if (result == null || result.pillar() != pillar) {
                throw new IllegalArgumentException(
                        "projection result key/pillar mismatch");
            }
        });
    }

    public String canonicalText() {
        StringBuilder value = new StringBuilder();
        value.append(inputSha256).append('|').append(seed).append('|')
                .append(requestedSamples).append('|').append(validSamples)
                .append('|').append(invalidSamples).append('\n');
        new TreeMap<>(diagnostics).forEach((name, number) -> value
                .append(name).append('=')
                .append(Double.toHexString(number)).append('\n'));
        new TreeMap<>(results).forEach((pillar, result) -> value.append(String.format(
                Locale.ROOT, "%d|%s|%s|%s|%s|%s|%s%n",
                pillar,
                Double.toHexString(result.readiness()),
                hexOrNull(result.etaP10()),
                hexOrNull(result.etaP50()),
                hexOrNull(result.etaP90()),
                Double.toHexString(result.censoredFraction()),
                result.momentum().name())));
        return value.toString();
    }

    private static Map<String, Double> immutableDiagnostics(
            Map<String, Double> source) {
        Map<String, Double> copy = new LinkedHashMap<>(
                Objects.requireNonNull(source, "diagnostics"));
        copy.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null
                    || !Double.isFinite(value)) {
                throw new IllegalArgumentException("invalid projection diagnostic");
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String hexOrNull(Double value) {
        return value == null ? "null" : Double.toHexString(value);
    }
}
