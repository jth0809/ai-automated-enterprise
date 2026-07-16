package com.aienterprise.backend.tracker.layerb;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.aienterprise.backend.tracker.domain.LayerBMetric;

/**
 * Turns parsed Launch Library 2 launches into measured Layer B cadence metrics
 * for a year: orbital launch count and success rate. These are genuine
 * measurements (basis MEASURED), never capability events -- classifying a launch
 * into a capability advance needs the deep classifier, deferred to LIVE_MODEL.
 */
public class LaunchCadenceAggregator {

    public List<LayerBMetric> aggregate(int year, List<LaunchRecord> launches, LocalDate accessedOn) {
        List<LaunchRecord> inYear = new ArrayList<>();
        for (LaunchRecord launch : launches) {
            if (launch.net() != null
                    && launch.net().atZone(ZoneOffset.UTC).getYear() == year
                    && isCompletedAttempt(launch)) {
                inYear.add(launch);
            }
        }
        if (inYear.isEmpty()) {
            return List.of();
        }
        long success = inYear.stream().filter(LaunchRecord::successful).count();
        LocalDate observedOn = LocalDate.of(year, 12, 31);
        List<LayerBMetric> metrics = new ArrayList<>();
        metrics.add(metric("ANNUAL_LAUNCH_COUNT", observedOn,
                BigDecimal.valueOf(inYear.size()), "LAUNCHES", accessedOn,
                "Orbital launch attempts counted from the Launch Library 2 feed for the year."));
        BigDecimal rate = BigDecimal.valueOf(success * 100L)
                .divide(BigDecimal.valueOf(inYear.size()), 2, RoundingMode.HALF_UP);
        metrics.add(metric("ANNUAL_LAUNCH_SUCCESS_RATE", observedOn, rate, "PERCENT", accessedOn,
                "Share of counted launches marked successful in the Launch Library 2 feed for the year."));
        if (inYear.stream().allMatch(LaunchCadenceAggregator::hasVehicleConfiguration)) {
            long falconFamilyLaunches = inYear.stream()
                    .filter(LaunchCadenceAggregator::isFalconFamily)
                    .count();
            metrics.add(metric("ANNUAL_FALCON_FAMILY_LAUNCH_COUNT", observedOn,
                    BigDecimal.valueOf(falconFamilyLaunches), "LAUNCHES", accessedOn,
                    "Completed Falcon 9 and Falcon Heavy orbital launch attempts counted from "
                            + "the Launch Library 2 feed for the year."));
        }
        return metrics;
    }

    private static boolean isCompletedAttempt(LaunchRecord launch) {
        if (launch.successful()) {
            return true;
        }
        String status = launch.status() == null
                ? ""
                : launch.status().trim().toUpperCase(Locale.ROOT);
        return "FAILURE".equals(status) || "PARTIAL FAILURE".equals(status);
    }

    private static boolean isFalconFamily(LaunchRecord launch) {
        String configuration = launch.vehicleConfiguration() == null
                ? ""
                : launch.vehicleConfiguration().trim().toUpperCase(Locale.ROOT);
        return "FALCON 9".equals(configuration)
                || configuration.startsWith("FALCON 9 ")
                || "FALCON HEAVY".equals(configuration)
                || configuration.startsWith("FALCON HEAVY ");
    }

    private static boolean hasVehicleConfiguration(LaunchRecord launch) {
        return launch.vehicleConfiguration() != null
                && !launch.vehicleConfiguration().isBlank();
    }

    private static LayerBMetric metric(
            String code, LocalDate observedOn, BigDecimal value, String unit,
            LocalDate accessedOn, String summary) {
        String canonical = code + "|" + observedOn + "|" + value.toPlainString()
                + "|" + LaunchLibraryClient.BASE;
        return new LayerBMetric(0, code, 1, observedOn, value, unit, "MEASURED",
                "Launch Library 2 launch feed", LaunchLibraryClient.BASE, accessedOn,
                sha256(canonical), summary);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
