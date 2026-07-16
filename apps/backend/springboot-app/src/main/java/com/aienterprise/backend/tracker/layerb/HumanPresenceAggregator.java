package com.aienterprise.backend.tracker.layerb;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Pure UTC interval integration for completed orbital-population years. */
public final class HumanPresenceAggregator {

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);

    public List<HumanPresenceYear> aggregate(HumanPresenceDataset dataset) {
        List<HumanPresenceTransition> transitions = dataset.transitions();
        if (transitions.size() < 2) {
            throw new IllegalArgumentException("At least two human-presence transitions are required");
        }

        int firstYear = transitions.getFirst().timestampUtc()
                .atZone(ZoneOffset.UTC).getYear();
        int cutoffYear = dataset.completeThroughUtc()
                .atZone(ZoneOffset.UTC).getYear();
        List<HumanPresenceYear> result = new ArrayList<>();
        for (int year = firstYear; year < cutoffYear; year++) {
            Instant yearStart = LocalDate.of(year, 1, 1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant yearEnd = LocalDate.of(year + 1, 1, 1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            BigDecimal weightedSeconds = BigDecimal.ZERO;
            int maximum = 0;
            boolean covered = false;

            for (int index = 0; index + 1 < transitions.size(); index++) {
                HumanPresenceTransition current = transitions.get(index);
                Instant intervalStart = later(current.timestampUtc(), yearStart);
                Instant intervalEnd = earlier(
                        transitions.get(index + 1).timestampUtc(), yearEnd);
                intervalEnd = earlier(intervalEnd, dataset.completeThroughUtc());
                if (!intervalEnd.isAfter(intervalStart)) {
                    continue;
                }
                BigDecimal seconds = seconds(Duration.between(intervalStart, intervalEnd));
                weightedSeconds = weightedSeconds.add(
                        seconds.multiply(BigDecimal.valueOf(current.orbitPopulation())));
                maximum = Math.max(maximum, current.orbitPopulation());
                covered = true;
            }
            if (!covered) {
                throw new IllegalArgumentException("No population interval covers year " + year);
            }
            BigDecimal personDays = weightedSeconds.divide(
                    SECONDS_PER_DAY, 4, RoundingMode.HALF_UP);
            result.add(new HumanPresenceYear(year, personDays, maximum));
        }
        return List.copyOf(result);
    }

    private static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds())
                .add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }
}
