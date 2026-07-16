package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AdaptiveWindow {

    private static final double DAYS_PER_YEAR = 365.25;

    public Selection select(
            int pillar,
            List<StateChangeEvent> inputEvents,
            RegimeBreak regimeBreak,
            LocalDate asOf,
            Params params) {
        if (pillar < 1 || pillar > 6 || asOf == null || params == null) {
            throw new IllegalArgumentException("pillar, asOf, and params are required");
        }
        if (regimeBreak != null) {
            if (regimeBreak.pillar() != pillar) {
                throw new IllegalArgumentException("regime break belongs to another pillar");
            }
            if (regimeBreak.breakDate().isAfter(asOf)) {
                throw new IllegalArgumentException("regime break exceeds asOf cutoff");
            }
        }

        List<StateChangeEvent> events = new ArrayList<>(inputEvents == null
                ? List.of() : inputEvents);
        events.sort(Comparator.comparing(StateChangeEvent::occurredOn)
                .thenComparingLong(StateChangeEvent::eventId));
        for (StateChangeEvent event : events) {
            if (event.pillar() != pillar) {
                throw new IllegalArgumentException("state change belongs to another pillar");
            }
            if (event.occurredOn().isAfter(asOf)) {
                throw new IllegalArgumentException("state change exceeds asOf cutoff");
            }
        }

        LocalDate regimeStart = regimeBreak == null ? null : regimeBreak.breakDate();
        List<StateChangeEvent> regimeEvents = events.stream()
                .filter(event -> regimeStart == null
                        || !event.occurredOn().isBefore(regimeStart))
                .toList();
        List<LocalDate> dates = regimeEvents.stream()
                .map(StateChangeEvent::occurredOn)
                .distinct()
                .toList();
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            long days = ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
            if (days > 0) {
                intervals.add(days);
            }
        }

        Double medianYears = null;
        int windowYears = params.windowFixedYears();
        if (intervals.size() >= 2) {
            intervals.sort(Long::compareTo);
            double medianDays;
            int middle = intervals.size() / 2;
            if (intervals.size() % 2 == 0) {
                medianDays = (intervals.get(middle - 1) + intervals.get(middle)) / 2.0;
            } else {
                medianDays = intervals.get(middle);
            }
            medianYears = medianDays / DAYS_PER_YEAR;
            double unroundedYears = params.windowM() * medianYears;
            double nearestInteger = Math.rint(unroundedYears);
            // Day-granularity intervals around leap years can put an exact
            // annual cadence a fraction above an integer. Do not turn that
            // calendar artifact into an extra model year.
            double dayResolution = params.windowM() / DAYS_PER_YEAR;
            if (Math.abs(unroundedYears - nearestInteger) <= dayResolution) {
                unroundedYears = nearestInteger;
            }
            long adaptiveYears = (long) Math.ceil(unroundedYears);
            windowYears = (int) Math.max(
                    params.windowMinYears(),
                    Math.min(params.windowMaxYears(), adaptiveYears));
        }

        LocalDate windowStart = asOf.minusYears(windowYears);
        if (regimeStart != null && regimeStart.isAfter(windowStart)) {
            windowStart = regimeStart;
        }
        LocalDate selectedStart = windowStart;
        List<StateChangeEvent> selected = regimeEvents.stream()
                .filter(event -> !event.occurredOn().isBefore(selectedStart))
                .toList();
        List<LocalDate> rollbackDates = selected.stream()
                .filter(StateChangeEvent::rollback)
                .map(StateChangeEvent::occurredOn)
                .distinct()
                .sorted()
                .toList();

        return new Selection(
                windowYears, selected.size(), medianYears,
                windowStart, regimeStart, rollbackDates);
    }

    public record Selection(
            int windowYears,
            int eventsInWindow,
            Double medianIntervalYears,
            LocalDate windowStart,
            LocalDate regimeStart,
            List<LocalDate> rollbackDates) {

        public Selection {
            rollbackDates = List.copyOf(rollbackDates);
        }
    }
}
