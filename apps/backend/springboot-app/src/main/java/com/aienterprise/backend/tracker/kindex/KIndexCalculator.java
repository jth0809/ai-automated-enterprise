package com.aienterprise.backend.tracker.kindex;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure conversion from annual primary energy to a Kardashev display gauge. */
public final class KIndexCalculator {

    private static final BigDecimal WATT_HOURS_PER_TWH =
            new BigDecimal("1000000000000");
    private static final BigDecimal HOURS_PER_YEAR = new BigDecimal("8760");
    private static final BigDecimal TYPE_ONE_WATTS =
            new BigDecimal("10000000000000000");
    private static final BigDecimal MAX_LONG = BigDecimal.valueOf(Long.MAX_VALUE);

    public Calculation calculate(BigDecimal primaryEnergyTwh) {
        if (primaryEnergyTwh == null || primaryEnergyTwh.signum() <= 0
                || !Double.isFinite(primaryEnergyTwh.doubleValue())) {
            throw new IllegalArgumentException(
                    "primaryEnergyTwh must be positive and finite");
        }

        BigDecimal roundedWatts = primaryEnergyTwh.multiply(WATT_HOURS_PER_TWH)
                .divide(HOURS_PER_YEAR, 0, RoundingMode.HALF_UP);
        if (roundedWatts.compareTo(MAX_LONG) > 0) {
            throw new IllegalArgumentException("powerWatts exceeds supported range");
        }
        long powerWatts = roundedWatts.longValueExact();
        double rawK = (Math.log10(powerWatts) - 6.0) / 10.0;
        if (!Double.isFinite(rawK)) {
            throw new IllegalArgumentException("calculated K-index is not finite");
        }

        BigDecimal fullK = BigDecimal.valueOf(rawK);
        BigDecimal kValue = fullK.setScale(4, RoundingMode.HALF_UP);
        BigDecimal typeOneGap = BigDecimal.ONE.subtract(fullK)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal typeOneMultiplier = TYPE_ONE_WATTS
                .divide(roundedWatts, 1, RoundingMode.HALF_UP);
        return new Calculation(
                powerWatts, kValue, typeOneGap, typeOneMultiplier);
    }

    public record Calculation(
            long powerWatts,
            BigDecimal kValue,
            BigDecimal typeOneGap,
            BigDecimal typeOneMultiplier) {
    }
}
