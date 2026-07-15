package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Pure, deterministic Wright-law projection over audited transport inputs. */
public final class WrightProjectionCalculator {

    private static final String INTERVAL_KIND = "ASSUMPTION_SENSITIVITY";
    private static final String BASIS = "PUBLISHED_PRICE";
    private static final String PRICE_MEANING =
            "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD";
    private static final String PROJECTION_LABEL =
            "Declared-assumption scenario; not provider internal cost";

    private static final int MIN_FRONTIER_OBSERVATIONS = 3;
    private static final int ESTABLISHED_FRONTIER_OBSERVATIONS = 5;
    private static final double NUMERIC_EPSILON = 1.0e-12;
    private static final BigDecimal MAX_PERSISTED_REQUIRED_LAUNCHES =
            new BigDecimal("9999999999999999.9999");

    public TransportProjection calculate(
            LocalDate asOfDate,
            TransportAssumption assumption,
            List<TransportPriceObservation> observations,
            List<AnnualLaunchCount> annualCounts) {
        Objects.requireNonNull(asOfDate, "asOfDate");
        Objects.requireNonNull(assumption, "assumption");

        if (!validAssumption(assumption)) {
            return insufficient(asOfDate, assumption, 0, 0,
                    "INVALID_ASSUMPTION");
        }

        FrontierResult frontierResult = annualFrontier(observations, asOfDate);
        if (!frontierResult.valid()) {
            return insufficient(asOfDate, assumption,
                    frontierResult.observations().size(), 0, "INVALID_INPUT");
        }

        List<TransportPriceObservation> frontier = frontierResult.observations();
        if (frontier.size() < MIN_FRONTIER_OBSERVATIONS) {
            return insufficient(asOfDate, assumption, frontier.size(), 0,
                    "INSUFFICIENT_OBSERVATIONS");
        }
        if (!strictlyIncreasingCumulative(frontier)) {
            return insufficient(asOfDate, assumption, frontier.size(), 0,
                    "INVALID_INPUT");
        }

        CadenceResult cadence = cadence(annualCounts, asOfDate);
        if (!cadence.valid()) {
            return insufficient(asOfDate, assumption, frontier.size(),
                    cadence.currentCumulative(), "INSUFFICIENT_ANNUAL_COUNTS");
        }
        long frontierCumulative = frontier.get(frontier.size() - 1)
                .cumulativeFamilyLaunches();
        if (cadence.currentCumulative() < frontierCumulative) {
            return insufficient(asOfDate, assumption, frontier.size(),
                    cadence.currentCumulative(), "INVALID_INPUT");
        }

        Fit fit = fit(frontier);
        if (!fit.valid()) {
            return insufficient(asOfDate, assumption, frontier.size(),
                    cadence.currentCumulative(), "INVALID_INPUT");
        }

        String tier = frontier.size() >= ESTABLISHED_FRONTIER_OBSERVATIONS
                ? "ESTABLISHED" : "PROVISIONAL";
        TreeSet<String> flags = new TreeSet<>();
        if (fit.rSquared() < assumption.weakFitR2().doubleValue()) {
            flags.add("WEAK_FIT");
        }

        BigDecimal centralTarget = assumption.centralTargetUsdPerKg();
        BigDecimal easyTarget = assumption.easyTargetUsdPerKg();
        BigDecimal hardTarget = assumption.hardTargetUsdPerKg();
        boolean centralReached = reached(frontier, centralTarget);

        if (!centralReached && fit.beta() >= 0.0) {
            return projection(asOfDate, assumption, "NON_DECLINING", tier,
                    List.copyOf(flags), frontier.size(), fit, cadence,
                    Scenario.unavailable(), Scenario.unavailable(),
                    Scenario.unavailable(), "NON_DECLINING_FIT");
        }

        Scenario central = scenario(asOfDate, assumption, frontier, centralTarget,
                fit, cadence.currentCumulative(), cadence.central());
        Scenario earliest = scenario(asOfDate, assumption, frontier, easyTarget,
                fit, cadence.currentCumulative(), cadence.fast());
        Scenario latest = scenario(asOfDate, assumption, frontier, hardTarget,
                fit, cadence.currentCumulative(), cadence.slow());

        String status;
        String reasonCode;
        if (centralReached) {
            status = "REACHED";
            reasonCode = "CENTRAL_TARGET_REACHED";
        } else if (central.beyondHorizon()) {
            status = "BEYOND_HORIZON";
            reasonCode = "CENTRAL_BEYOND_HORIZON";
        } else {
            status = tier;
            reasonCode = "FINITE_PROJECTION";
        }
        return projection(asOfDate, assumption, status, tier,
                List.copyOf(flags), frontier.size(), fit, cadence,
                central, earliest, latest, reasonCode);
    }

    private static TransportProjection projection(
            LocalDate asOfDate,
            TransportAssumption assumption,
            String status,
            String tier,
            List<String> flags,
            int observationCount,
            Fit fit,
            CadenceResult cadence,
            Scenario central,
            Scenario earliest,
            Scenario latest,
            String reasonCode) {
        return new TransportProjection(
                0, asOfDate, assumption.version(), assumption.modelVersion(),
                status, tier, flags, observationCount,
                round(fit.alpha(), 10), round(fit.beta(), 10),
                round(fit.rSquared(), 10), cadence.currentCumulative(),
                round(cadence.central(), 4), round(cadence.fast(), 4),
                round(cadence.slow(), 4),
                assumption.centralTargetUsdPerKg(),
                assumption.easyTargetUsdPerKg(),
                assumption.hardTargetUsdPerKg(),
                central.requiredLaunches(), earliest.requiredLaunches(),
                latest.requiredLaunches(), central.etaYear(),
                earliest.etaYear(), latest.etaYear(),
                central.beyondHorizon(), earliest.beyondHorizon(),
                latest.beyondHorizon(), assumption.priceBasisYear(),
                assumption.horizonYears(), INTERVAL_KIND, BASIS, PRICE_MEANING,
                PROJECTION_LABEL, reasonCode);
    }

    private static TransportProjection insufficient(
            LocalDate asOfDate,
            TransportAssumption assumption,
            int observationCount,
            long currentCumulative,
            String reasonCode) {
        return new TransportProjection(
                0, asOfDate, assumption.version(), assumption.modelVersion(),
                "INSUFFICIENT_DATA", "INSUFFICIENT_DATA", List.of(),
                observationCount, null, null, null, currentCumulative,
                null, null, null,
                assumption.centralTargetUsdPerKg(),
                assumption.easyTargetUsdPerKg(),
                assumption.hardTargetUsdPerKg(),
                null, null, null, null, null, null,
                false, false, false, assumption.priceBasisYear(),
                assumption.horizonYears(), INTERVAL_KIND, BASIS, PRICE_MEANING,
                PROJECTION_LABEL, reasonCode);
    }

    private static FrontierResult annualFrontier(
            List<TransportPriceObservation> observations, LocalDate asOfDate) {
        if (observations == null) {
            return new FrontierResult(false, List.of());
        }
        Map<Integer, TransportPriceObservation> byYear = new TreeMap<>();
        Comparator<TransportPriceObservation> comparator = Comparator
                .comparing(TransportPriceObservation::realBasisUsdPerKg)
                .thenComparing(TransportPriceObservation::vehicleVariant);
        for (TransportPriceObservation observation : observations) {
            if (!validObservation(observation, asOfDate)) {
                return new FrontierResult(false, List.copyOf(byYear.values()));
            }
            byYear.merge(observation.observationYear(), observation,
                    (left, right) -> comparator.compare(left, right) <= 0
                            ? left : right);
        }
        return new FrontierResult(true, List.copyOf(byYear.values()));
    }

    private static boolean validObservation(
            TransportPriceObservation observation, LocalDate asOfDate) {
        if (observation == null
                || observation.observationYear() <= 0
                || observation.observationYear() > asOfDate.getYear()
                || observation.cumulativeFamilyLaunches() <= 0
                || blank(observation.vehicleVariant())) {
            return false;
        }
        return positiveFinite(observation.publishedPriceUsd())
                && positiveFinite(observation.maxLeoPayloadKg())
                && positiveFinite(observation.nominalUsdPerKg())
                && positiveFinite(observation.cpiObservationValue())
                && positiveFinite(observation.cpiBasisValue())
                && positiveFinite(observation.realBasisUsdPerKg());
    }

    private static boolean strictlyIncreasingCumulative(
            List<TransportPriceObservation> observations) {
        long previous = -1;
        for (TransportPriceObservation observation : observations) {
            if (observation.cumulativeFamilyLaunches() <= previous) {
                return false;
            }
            previous = observation.cumulativeFamilyLaunches();
        }
        return true;
    }

    private static CadenceResult cadence(
            List<AnnualLaunchCount> annualCounts, LocalDate asOfDate) {
        if (annualCounts == null || annualCounts.size() < 3) {
            return CadenceResult.invalid(0);
        }
        Map<Integer, Long> byYear = new TreeMap<>();
        long cumulative = 0;
        try {
            for (AnnualLaunchCount count : annualCounts) {
                if (count == null || count.year() <= 0
                        || count.year() >= asOfDate.getYear()
                        || count.launches() < 0
                        || byYear.putIfAbsent(count.year(), count.launches()) != null) {
                    return CadenceResult.invalid(cumulative);
                }
                cumulative = Math.addExact(cumulative, count.launches());
            }
        } catch (ArithmeticException overflow) {
            return CadenceResult.invalid(0);
        }
        List<Map.Entry<Integer, Long>> rows = new ArrayList<>(byYear.entrySet());
        for (int index = 1; index < rows.size(); index++) {
            if (rows.get(index).getKey() != rows.get(index - 1).getKey() + 1) {
                return CadenceResult.invalid(cumulative);
            }
        }

        List<Double> available = new ArrayList<>();
        Double mean3 = trailingMean(rows, 3);
        Double mean5 = trailingMean(rows, 5);
        Double mean10 = trailingMean(rows, 10);
        if (mean3 != null) {
            available.add(mean3);
        }
        if (mean5 != null) {
            available.add(mean5);
        }
        if (mean10 != null) {
            available.add(mean10);
        }
        double central = mean5 != null ? mean5 : mean3 == null ? 0.0 : mean3;
        double fast = available.stream().mapToDouble(Double::doubleValue)
                .max().orElse(0.0);
        double slow = available.stream().mapToDouble(Double::doubleValue)
                .min().orElse(0.0);
        boolean valid = cumulative > 0
                && Double.isFinite(central) && central > 0.0
                && Double.isFinite(fast) && fast > 0.0
                && Double.isFinite(slow) && slow > 0.0;
        return new CadenceResult(valid, cumulative, central, fast, slow);
    }

    private static Double trailingMean(
            List<Map.Entry<Integer, Long>> rows, int window) {
        if (rows.size() < window) {
            return null;
        }
        double total = 0.0;
        for (int index = rows.size() - window; index < rows.size(); index++) {
            total += rows.get(index).getValue();
        }
        double mean = total / window;
        return Double.isFinite(mean) ? mean : null;
    }

    private static Fit fit(List<TransportPriceObservation> frontier) {
        List<Point> points = frontier.stream()
                .map(observation -> new Point(
                        Math.log(observation.cumulativeFamilyLaunches()),
                        Math.log(observation.realBasisUsdPerKg().doubleValue())))
                .toList();
        if (points.stream().anyMatch(point -> !Double.isFinite(point.x())
                || !Double.isFinite(point.y()))) {
            return Fit.invalid();
        }
        double meanX = points.stream().mapToDouble(Point::x).average().orElseThrow();
        double meanY = points.stream().mapToDouble(Point::y).average().orElseThrow();
        double sxx = points.stream()
                .mapToDouble(point -> square(point.x() - meanX)).sum();
        double sxy = points.stream()
                .mapToDouble(point -> (point.x() - meanX) * (point.y() - meanY))
                .sum();
        if (!Double.isFinite(sxx) || sxx <= NUMERIC_EPSILON
                || !Double.isFinite(sxy)) {
            return Fit.invalid();
        }
        double beta = sxy / sxx;
        double alpha = meanY - beta * meanX;
        double sst = points.stream()
                .mapToDouble(point -> square(point.y() - meanY)).sum();
        double sse = points.stream()
                .mapToDouble(point -> square(
                        point.y() - (alpha + beta * point.x())))
                .sum();
        double rSquared = sst <= NUMERIC_EPSILON ? 1.0 : 1.0 - sse / sst;
        rSquared = Math.max(0.0, Math.min(1.0, rSquared));
        boolean valid = Double.isFinite(alpha) && Double.isFinite(beta)
                && Double.isFinite(rSquared);
        return new Fit(valid, alpha, beta, rSquared);
    }

    private static Scenario scenario(
            LocalDate asOfDate,
            TransportAssumption assumption,
            List<TransportPriceObservation> frontier,
            BigDecimal target,
            Fit fit,
            long currentCumulative,
            double cadence) {
        Double requiredLaunches = null;
        double required = Double.NaN;
        if (fit.beta() < 0.0) {
            required = Math.exp(
                    (Math.log(target.doubleValue()) - fit.alpha()) / fit.beta());
            requiredLaunches = roundRequiredLaunches(required);
        }
        for (TransportPriceObservation observation : frontier) {
            if (observation.realBasisUsdPerKg().compareTo(target) <= 0) {
                return new Scenario(
                        requiredLaunches,
                        round((double) observation.observationYear(), 1), false);
            }
        }
        if (fit.beta() >= 0.0) {
            return Scenario.unavailable();
        }
        double eta = asOfDate.getYear()
                + Math.max(0.0, required - currentCumulative) / cadence;
        boolean beyond = requiredLaunches == null
                || !Double.isFinite(eta)
                || eta > asOfDate.getYear() + assumption.horizonYears();
        return new Scenario(
                requiredLaunches,
                beyond ? null : round(eta, 1), beyond);
    }

    private static boolean reached(
            List<TransportPriceObservation> frontier, BigDecimal target) {
        return frontier.stream().anyMatch(observation ->
                observation.realBasisUsdPerKg().compareTo(target) <= 0);
    }

    private static boolean validAssumption(TransportAssumption assumption) {
        if (blank(assumption.version()) || blank(assumption.modelVersion())
                || !positiveFinite(assumption.centralTargetUsdPerKg())
                || !positiveFinite(assumption.easyTargetUsdPerKg())
                || !positiveFinite(assumption.hardTargetUsdPerKg())
                || assumption.easyTargetUsdPerKg().compareTo(
                        assumption.centralTargetUsdPerKg()) <= 0
                || assumption.centralTargetUsdPerKg().compareTo(
                        assumption.hardTargetUsdPerKg()) <= 0
                || assumption.priceBasisYear() < 2000
                || assumption.priceBasisYear() > 9999
                || assumption.horizonYears() <= 0
                || assumption.horizonYears() > 150
                || !nonNegativeFinite(assumption.weakFitR2())
                || assumption.weakFitR2().compareTo(BigDecimal.ONE) > 0
                || !positiveFinite(assumption.wideningFactor())
                || assumption.wideningFactor().compareTo(BigDecimal.ONE) < 0) {
            return false;
        }
        return true;
    }

    private static boolean positiveFinite(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return false;
        }
        return Double.isFinite(value.doubleValue());
    }

    private static boolean nonNegativeFinite(BigDecimal value) {
        return value != null && value.signum() >= 0
                && Double.isFinite(value.doubleValue());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Double round(double value, int scale) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value)
                .setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double roundRequiredLaunches(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return null;
        }
        BigDecimal rounded = BigDecimal.valueOf(value)
                .setScale(4, RoundingMode.HALF_UP);
        return rounded.compareTo(MAX_PERSISTED_REQUIRED_LAUNCHES) <= 0
                ? rounded.doubleValue() : null;
    }

    private static double square(double value) {
        return value * value;
    }

    private record FrontierResult(
            boolean valid, List<TransportPriceObservation> observations) {
    }

    private record CadenceResult(
            boolean valid,
            long currentCumulative,
            double central,
            double fast,
            double slow) {

        private static CadenceResult invalid(long currentCumulative) {
            return new CadenceResult(false, currentCumulative, 0.0, 0.0, 0.0);
        }
    }

    private record Fit(
            boolean valid, double alpha, double beta, double rSquared) {

        private static Fit invalid() {
            return new Fit(false, 0.0, 0.0, 0.0);
        }
    }

    private record Scenario(
            Double requiredLaunches, Double etaYear, boolean beyondHorizon) {

        private static Scenario unavailable() {
            return new Scenario(null, null, false);
        }
    }

    private record Point(double x, double y) {
    }
}
