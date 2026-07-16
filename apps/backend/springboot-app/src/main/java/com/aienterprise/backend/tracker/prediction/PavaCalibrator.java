package com.aienterprise.backend.tracker.prediction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic unweighted pool-adjacent-violators probability calibration. */
public final class PavaCalibrator {

    private static final MathContext DECIMAL = MathContext.DECIMAL128;

    public Model fit(List<Sample> samples) {
        List<Sample> sorted = new ArrayList<>(List.copyOf(
                Objects.requireNonNull(samples, "samples")));
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("PAVA requires observations");
        }
        sorted.sort(Comparator.comparingDouble(Sample::rawProbability)
                .thenComparingInt(Sample::outcome));

        List<Block> blocks = new ArrayList<>();
        for (Sample sample : sorted) {
            if (!blocks.isEmpty()
                    && Double.compare(blocks.getLast().lastX,
                            sample.rawProbability()) == 0) {
                blocks.getLast().add(sample);
            } else {
                blocks.add(new Block(sample));
            }
        }
        int index = 1;
        while (index < blocks.size()) {
            if (blocks.get(index - 1).meanOutcome()
                    <= blocks.get(index).meanOutcome()) {
                index++;
                continue;
            }
            blocks.get(index - 1).merge(blocks.remove(index));
            if (index > 1) {
                index--;
            }
        }

        List<Knot> knots = blocks.stream().map(Block::knot).toList();
        return new Model(knots);
    }

    public record Sample(double rawProbability, int outcome) {

        public Sample {
            if (!unit(rawProbability) || outcome < 0 || outcome > 1) {
                throw new IllegalArgumentException("invalid PAVA sample");
            }
        }
    }

    public record Knot(double x, double y) {

        public Knot {
            if (!unit(x) || !unit(y)) {
                throw new IllegalArgumentException("invalid PAVA knot");
            }
        }
    }

    public record Model(List<Knot> knots) {

        private static final Pattern KNOT_PATTERN = Pattern.compile(
                "\\{\\\"x\\\":([^,}]+),\\\"y\\\":([^}]+)}");

        public Model {
            knots = List.copyOf(Objects.requireNonNull(knots, "knots"));
            if (knots.isEmpty()) {
                throw new IllegalArgumentException("PAVA model needs knots");
            }
            for (int index = 1; index < knots.size(); index++) {
                if (knots.get(index).x() <= knots.get(index - 1).x()
                        || knots.get(index).y() < knots.get(index - 1).y()) {
                    throw new IllegalArgumentException(
                            "PAVA knots must be strictly ordered and monotone");
                }
            }
        }

        public double apply(double rawProbability) {
            if (!unit(rawProbability)) {
                throw new IllegalArgumentException(
                        "calibration input must be a probability");
            }
            if (rawProbability <= knots.getFirst().x()) {
                return knots.getFirst().y();
            }
            if (rawProbability >= knots.getLast().x()) {
                return knots.getLast().y();
            }
            for (int index = 1; index < knots.size(); index++) {
                Knot right = knots.get(index);
                if (rawProbability <= right.x()) {
                    Knot left = knots.get(index - 1);
                    double fraction = (rawProbability - left.x())
                            / (right.x() - left.x());
                    return left.y() + fraction * (right.y() - left.y());
                }
            }
            throw new IllegalStateException("unreachable calibration interval");
        }

        public String toJson() {
            StringBuilder json = new StringBuilder("[");
            for (int index = 0; index < knots.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                Knot knot = knots.get(index);
                json.append("{\"x\":").append(decimal(knot.x()))
                        .append(",\"y\":").append(decimal(knot.y()))
                        .append('}');
            }
            return json.append(']').toString();
        }

        public static Model fromJson(String json) {
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("PAVA knot JSON is required");
            }
            List<Knot> knots = new ArrayList<>();
            Matcher matcher = KNOT_PATTERN.matcher(json);
            while (matcher.find()) {
                knots.add(new Knot(Double.parseDouble(matcher.group(1)),
                        Double.parseDouble(matcher.group(2))));
            }
            Model model = new Model(knots);
            if (!model.toJson().equals(json)) {
                throw new IllegalArgumentException("non-canonical PAVA knot JSON");
            }
            return model;
        }

        private static String decimal(double value) {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
    }

    private static final class Block {

        private BigDecimal xSum;
        private double outcomeSum;
        private int count;
        private double lastX;

        private Block(Sample sample) {
            xSum = BigDecimal.valueOf(sample.rawProbability());
            outcomeSum = sample.outcome();
            count = 1;
            lastX = sample.rawProbability();
        }

        private void add(Sample sample) {
            xSum = xSum.add(BigDecimal.valueOf(sample.rawProbability()));
            outcomeSum += sample.outcome();
            count++;
            lastX = sample.rawProbability();
        }

        private void merge(Block other) {
            xSum = xSum.add(other.xSum);
            outcomeSum += other.outcomeSum;
            count += other.count;
            lastX = other.lastX;
        }

        private double meanOutcome() {
            return outcomeSum / count;
        }

        private Knot knot() {
            double meanX = xSum.divide(BigDecimal.valueOf(count), DECIMAL)
                    .doubleValue();
            return new Knot(meanX, meanOutcome());
        }
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
