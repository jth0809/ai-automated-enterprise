package com.aienterprise.backend.tracker.transport;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

/** Persists quarterly coherence and bounded event references without domain writes. */
@Service
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class TransportCoherenceService {

    private static final Logger log = LoggerFactory.getLogger(
            TransportCoherenceService.class);

    private final TransportEconomicsRepository transportRepository;
    private final TrackerRepository trackerRepository;
    private final TransportCoherenceCalculator calculator;

    @Autowired
    public TransportCoherenceService(
            TransportEconomicsRepository transportRepository,
            TrackerRepository trackerRepository) {
        this(transportRepository, trackerRepository,
                new TransportCoherenceCalculator());
    }

    TransportCoherenceService(
            TransportEconomicsRepository transportRepository,
            TrackerRepository trackerRepository,
            TransportCoherenceCalculator calculator) {
        this.transportRepository = Objects.requireNonNull(
                transportRepository, "transportRepository");
        this.trackerRepository = Objects.requireNonNull(
                trackerRepository, "trackerRepository");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    @Transactional
    public TransportCoherenceReport runForQuarter(LocalDate reportPeriodEnd) {
        Objects.requireNonNull(reportPeriodEnd, "reportPeriodEnd");
        Optional<TransportCoherenceReport> latestBeforeRun =
                transportRepository.findLatestCoherenceReport();
        boolean existingCurrent = latestBeforeRun
                .map(report -> reportPeriodEnd.equals(report.reportPeriodEnd()))
                .orElse(false);
        TransportProjection projection = transportRepository
                .findLatestProjection().orElse(null);
        List<AnnualLaunchCount> counts = transportRepository
                .findAnnualFalconLaunchCounts();
        SnapshotRow snapshot = trackerRepository.findLatestSnapshot(1).orElse(null);
        TransportCoherenceReport previous = transportRepository
                .findPreviousCoherenceReport(reportPeriodEnd).orElse(null);

        TransportCoherenceReport calculated = calculator.calculate(
                reportPeriodEnd, projection, counts, snapshot, previous);
        long reportId = transportRepository.saveCoherenceReport(calculated);
        TransportCoherenceReport saved = withId(calculated, reportId);

        int sampleCount = 0;
        if (saved.alertActive() && !existingCurrent) {
            LocalDate watchedStart = watchedStartExclusive(saved, previous);
            for (long eventId : trackerRepository.findConfirmedPillarOneEventIds(
                    watchedStart, reportPeriodEnd)) {
                transportRepository.insertSample(reportId, eventId);
            }
        }
        if (saved.alertActive()) {
            sampleCount = transportRepository.findSamples(reportId).size();
        }

        boolean firstEntry = saved.alertActive()
                && (previous == null || !"DIVERGENT".equals(previous.state())
                        || !saved.polarity().equals(previous.polarity()));
        if (firstEntry && !existingCurrent) {
            log.warn("tracker transport coherence divergent period={} polarity={} "
                            + "streak={} sampleCount={}",
                    saved.reportPeriodEnd(), saved.polarity(),
                    saved.consecutiveQuarterStreak(), sampleCount);
        }
        return saved;
    }

    private static LocalDate watchedStartExclusive(
            TransportCoherenceReport current,
            TransportCoherenceReport previous) {
        if (previous != null && "WATCH".equals(previous.state())
                && previous.reportPeriodEnd().plusMonths(3)
                        .equals(current.reportPeriodEnd())
                && previous.polarity().equals(current.polarity())) {
            return previous.reportPeriodEnd();
        }
        LocalDate firstDivergent = current.firstDivergentPeriod();
        return (firstDivergent == null
                ? current.reportPeriodEnd() : firstDivergent).minusMonths(3);
    }

    private static TransportCoherenceReport withId(
            TransportCoherenceReport value, long id) {
        return new TransportCoherenceReport(
                id, value.reportPeriodEnd(), value.layerCSnapshotDate(),
                value.priceDirection(), value.cadenceDirection(),
                value.layerBDirection(), value.layerCDirection(), value.state(),
                value.polarity(), value.consecutiveQuarterStreak(),
                value.alertActive(), value.wideningFactor(),
                value.firstDivergentPeriod());
    }
}
