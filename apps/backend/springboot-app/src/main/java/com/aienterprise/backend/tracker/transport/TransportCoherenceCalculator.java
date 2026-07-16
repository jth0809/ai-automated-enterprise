package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

/** Pure direction and consecutive-quarter calculation for the approved B pair. */
public final class TransportCoherenceCalculator {

    private static final double DIRECTION_EPSILON = 1.0e-6;
    private static final double CADENCE_EPSILON = 0.01;

    public TransportCoherenceReport calculate(
            LocalDate reportPeriodEnd,
            TransportProjection projection,
            List<AnnualLaunchCount> annualCounts,
            SnapshotRow layerCSnapshot,
            TransportCoherenceReport previous) {
        Objects.requireNonNull(reportPeriodEnd, "reportPeriodEnd");

        String priceDirection = priceDirection(projection, reportPeriodEnd);
        String cadenceDirection = cadenceDirection(annualCounts, reportPeriodEnd);
        String layerBDirection = layerBDirection(priceDirection, cadenceDirection);
        String layerCDirection = layerCDirection(layerCSnapshot, reportPeriodEnd);

        boolean insufficient = "INSUFFICIENT_DATA".equals(layerBDirection)
                || "INSUFFICIENT_DATA".equals(layerCDirection);
        String polarity = insufficient || "MIXED".equals(layerBDirection)
                ? "NONE" : polarity(layerBDirection, layerCDirection);
        boolean consecutive = previous != null
                && previous.reportPeriodEnd() != null
                && previous.reportPeriodEnd().plusMonths(3).equals(reportPeriodEnd);
        boolean samePolarity = consecutive
                && polarity.equals(previous.polarity());
        int streak = "NONE".equals(polarity) ? 0
                : samePolarity ? previous.consecutiveQuarterStreak() + 1 : 1;

        String state = "MIXED".equals(layerBDirection) ? "MIXED"
                : insufficient ? "INSUFFICIENT_DATA"
                : "NONE".equals(polarity) ? "COHERENT"
                : streak >= 2 ? "DIVERGENT" : "WATCH";
        boolean alert = "DIVERGENT".equals(state);
        BigDecimal widening = alert
                ? new BigDecimal("1.50") : new BigDecimal("1.00");
        LocalDate firstDivergent = firstDivergent(
                reportPeriodEnd, previous, consecutive, samePolarity, alert);

        return new TransportCoherenceReport(
                0, reportPeriodEnd,
                layerCSnapshot == null ? null : layerCSnapshot.snapshotDate(),
                priceDirection, cadenceDirection, layerBDirection, layerCDirection,
                state, polarity, streak, alert, widening, firstDivergent);
    }

    private static String priceDirection(
            TransportProjection projection, LocalDate reportPeriodEnd) {
        if (projection == null || projection.beta() == null
                || !Double.isFinite(projection.beta())
                || projection.asOfDate() == null
                || projection.asOfDate().isAfter(reportPeriodEnd)) {
            return "INSUFFICIENT_DATA";
        }
        return direction(projection.beta(), DIRECTION_EPSILON, true);
    }

    private static String cadenceDirection(
            List<AnnualLaunchCount> annualCounts, LocalDate reportPeriodEnd) {
        if (annualCounts == null) {
            return "INSUFFICIENT_DATA";
        }
        Map<Integer, Long> byYear = new TreeMap<>();
        for (AnnualLaunchCount count : annualCounts) {
            if (count == null || count.year() <= 0 || count.launches() < 0
                    || byYear.putIfAbsent(count.year(), count.launches()) != null) {
                return "INSUFFICIENT_DATA";
            }
        }
        List<AnnualLaunchCount> eligible = byYear.entrySet().stream()
                .filter(entry -> entry.getKey() < reportPeriodEnd.getYear())
                .map(entry -> new AnnualLaunchCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(AnnualLaunchCount::year))
                .toList();
        if (eligible.size() < 3) {
            return "INSUFFICIENT_DATA";
        }
        int window = eligible.size() >= 5 ? 5 : 3;
        List<AnnualLaunchCount> points = new ArrayList<>(
                eligible.subList(eligible.size() - window, eligible.size()));
        for (int index = 1; index < points.size(); index++) {
            if (points.get(index).year() != points.get(index - 1).year() + 1) {
                return "INSUFFICIENT_DATA";
            }
        }
        double meanX = points.stream().mapToDouble(AnnualLaunchCount::year)
                .average().orElseThrow();
        double meanY = points.stream().mapToDouble(point -> Math.log1p(point.launches()))
                .average().orElseThrow();
        double sxx = points.stream()
                .mapToDouble(point -> square(point.year() - meanX)).sum();
        double sxy = points.stream()
                .mapToDouble(point -> (point.year() - meanX)
                        * (Math.log1p(point.launches()) - meanY))
                .sum();
        if (!Double.isFinite(sxx) || sxx <= 0.0 || !Double.isFinite(sxy)) {
            return "INSUFFICIENT_DATA";
        }
        double slope = sxy / sxx;
        if (!Double.isFinite(slope)) {
            return "INSUFFICIENT_DATA";
        }
        return direction(slope, CADENCE_EPSILON, false);
    }

    private static String layerBDirection(String price, String cadence) {
        if ("INSUFFICIENT_DATA".equals(price)
                || "INSUFFICIENT_DATA".equals(cadence)) {
            return "INSUFFICIENT_DATA";
        }
        if (price.equals(cadence)) {
            return price;
        }
        if ("FLAT".equals(price)) {
            return cadence;
        }
        if ("FLAT".equals(cadence)) {
            return price;
        }
        return "MIXED";
    }

    private static String layerCDirection(
            SnapshotRow snapshot, LocalDate reportPeriodEnd) {
        if (snapshot == null || snapshot.pillar() != 1
                || snapshot.snapshotDate() == null
                || snapshot.snapshotDate().isAfter(reportPeriodEnd)
                || snapshot.trendUsed() == null
                || !Double.isFinite(snapshot.trendUsed())) {
            return "INSUFFICIENT_DATA";
        }
        return direction(snapshot.trendUsed(), DIRECTION_EPSILON, false);
    }

    private static String polarity(String layerB, String layerC) {
        int comparison = Integer.compare(rank(layerB), rank(layerC));
        return comparison == 0 ? "NONE"
                : comparison > 0 ? "B_AHEAD" : "C_AHEAD";
    }

    private static int rank(String direction) {
        return switch (direction) {
            case "ADVANCING" -> 1;
            case "FLAT" -> 0;
            case "REGRESSING" -> -1;
            default -> throw new IllegalArgumentException(
                    "unsupported coherence direction: " + direction);
        };
    }

    private static String direction(
            double value, double epsilon, boolean lowerIsAdvancing) {
        if (lowerIsAdvancing) {
            return value < -epsilon ? "ADVANCING"
                    : value > epsilon ? "REGRESSING" : "FLAT";
        }
        return value > epsilon ? "ADVANCING"
                : value < -epsilon ? "REGRESSING" : "FLAT";
    }

    private static LocalDate firstDivergent(
            LocalDate reportPeriodEnd,
            TransportCoherenceReport previous,
            boolean consecutive,
            boolean samePolarity,
            boolean alert) {
        if (!alert) {
            return null;
        }
        if (previous != null && consecutive && samePolarity
                && "DIVERGENT".equals(previous.state())
                && previous.firstDivergentPeriod() != null) {
            return previous.firstDivergentPeriod();
        }
        return reportPeriodEnd;
    }

    private static double square(double value) {
        return value * value;
    }
}
