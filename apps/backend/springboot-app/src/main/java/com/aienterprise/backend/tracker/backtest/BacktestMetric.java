package com.aienterprise.backend.tracker.backtest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class BacktestMetric {

    private BacktestMetric() {
    }

    public static Bundle aggregate(List<BacktestReport.FoldResult> input) {
        List<BacktestReport.FoldResult> folds = List.copyOf(
                Objects.requireNonNull(input, "folds"));
        Map<Key, Value> values = new LinkedHashMap<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            int currentPillar = pillar;
            List<BacktestReport.FoldResult> pillarFolds = folds.stream()
                    .filter(fold -> fold.pillar() == currentPillar)
                    .filter(fold -> fold.status() == Status.OK)
                    .sorted(Comparator.comparing(BacktestReport.FoldResult::cutoff)
                            .thenComparingInt(BacktestReport.FoldResult::foldIndex))
                    .toList();
            values.put(new Key(Code.READINESS_MAE, pillar), average(
                    Code.READINESS_MAE, pillar,
                    pillarFolds.stream().mapToDouble(fold -> Math.abs(
                            fold.predictedReadiness() - fold.actualReadiness())).boxed().toList()));
            values.put(new Key(Code.LOGIT_READINESS_MAE, pillar), average(
                    Code.LOGIT_READINESS_MAE, pillar,
                    pillarFolds.stream().mapToDouble(fold -> Math.abs(
                            fold.predictedLogit() - fold.actualLogit())).boxed().toList()));
            values.put(new Key(Code.DIRECTION_ACCURACY, pillar), average(
                    Code.DIRECTION_ACCURACY, pillar,
                    pillarFolds.stream().map(fold ->
                            fold.predictedAdvance().equals(fold.actualAdvance())
                                    ? 1.0 : 0.0).toList()));
            values.put(new Key(Code.INTERVAL_80_COVERAGE, pillar), average(
                    Code.INTERVAL_80_COVERAGE, pillar,
                    pillarFolds.stream().map(fold -> fold.covered() ? 1.0 : 0.0)
                            .toList()));

            List<Double> changes = new ArrayList<>();
            for (int index = 1; index < pillarFolds.size(); index++) {
                Double previous = pillarFolds.get(index - 1).etaYear();
                Double current = pillarFolds.get(index).etaYear();
                if (previous != null && current != null) {
                    changes.add(Math.abs(current - previous));
                }
            }
            values.put(new Key(Code.ETA_VOLATILITY_YEARS, pillar), average(
                    Code.ETA_VOLATILITY_YEARS, pillar, changes));
        }
        for (Code code : Code.values()) {
            List<Value> pillarValues = new ArrayList<>();
            for (int pillar = 1; pillar <= 6; pillar++) {
                Value value = values.get(new Key(code, pillar));
                if (value.status() == Status.OK) {
                    pillarValues.add(value);
                }
            }
            Value overall;
            if (pillarValues.isEmpty()) {
                overall = new Value(code, 0, null, 0, Status.INSUFFICIENT_DATA);
            } else {
                overall = new Value(
                        code, 0,
                        pillarValues.stream().mapToDouble(Value::value).average().orElseThrow(),
                        pillarValues.stream().mapToInt(Value::samples).sum(),
                        Status.OK);
            }
            values.put(new Key(code, 0), overall);
        }
        return new Bundle(values);
    }

    private static Value average(Code code, int pillar, List<Double> input) {
        List<Double> values = input.stream()
                .filter(Objects::nonNull)
                .filter(Double::isFinite)
                .toList();
        if (values.isEmpty()) {
            return new Value(code, pillar, null, 0, Status.INSUFFICIENT_DATA);
        }
        return new Value(
                code, pillar,
                values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                values.size(), Status.OK);
    }

    public enum Code {
        READINESS_MAE,
        LOGIT_READINESS_MAE,
        DIRECTION_ACCURACY,
        INTERVAL_80_COVERAGE,
        ETA_VOLATILITY_YEARS
    }

    public enum Status {
        OK,
        INSUFFICIENT_DATA
    }

    public record Key(Code code, int pillar) implements Comparable<Key> {
        public Key {
            code = Objects.requireNonNull(code, "code");
            if (pillar < 0 || pillar > 6) {
                throw new IllegalArgumentException("metric pillar must be 0..6");
            }
        }

        @Override
        public int compareTo(Key other) {
            int byCode = code.compareTo(other.code);
            return byCode != 0 ? byCode : Integer.compare(pillar, other.pillar);
        }
    }

    public record Value(
            Code code,
            int pillar,
            Double value,
            int samples,
            Status status) {

        public Value {
            code = Objects.requireNonNull(code, "code");
            status = Objects.requireNonNull(status, "status");
            if (pillar < 0 || pillar > 6 || samples < 0) {
                throw new IllegalArgumentException("invalid metric identity");
            }
            if (status == Status.OK) {
                if (value == null || !Double.isFinite(value)
                        || value < 0 || samples < 1) {
                    throw new IllegalArgumentException("invalid populated metric");
                }
            } else if (value != null || samples != 0) {
                throw new IllegalArgumentException("insufficient metric must be empty");
            }
        }
    }

    public record Bundle(Map<Key, Value> values) {
        public Bundle {
            Map<Key, Value> copied = new TreeMap<>();
            Objects.requireNonNull(values, "values").forEach((key, value) -> {
                if (!key.equals(new Key(value.code(), value.pillar()))) {
                    throw new IllegalArgumentException("metric key/value mismatch");
                }
                copied.put(key, value);
            });
            values = Collections.unmodifiableMap(copied);
        }

        public Value get(Code code, int pillar) {
            Value value = values.get(new Key(code, pillar));
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing metric " + code + " pillar " + pillar);
            }
            return value;
        }

        public Optional<Value> find(Code code, int pillar) {
            return Optional.ofNullable(values.get(new Key(code, pillar)));
        }
    }
}
