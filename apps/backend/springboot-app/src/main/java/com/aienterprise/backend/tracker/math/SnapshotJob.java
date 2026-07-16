package com.aienterprise.backend.tracker.math;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.OpsState;
import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.graph.CapabilityReadinessService;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.ops.StateFreezeService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class SnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotJob.class);
    private static final String DISPLAYED_ETA_KEY = "LAST_DISPLAYED_ETA";
    private static final double TARGET_READINESS = 0.85;
    private static final double Z_80_PERCENT = 1.2816;
    private static final double DAMPING_DAYS_PER_DAY = 90.0;

    private record PillarFit(double readiness, Double slope, Double etaYear, Double etaLow, Double etaHigh) {
    }

    private final TrackerRepository repository;
    private final StateFreezeService freezeService;
    private final CapabilityReadinessService readinessService;

    public SnapshotJob(
            TrackerRepository repository,
            StateFreezeService freezeService,
            CapabilityReadinessService readinessService) {
        this.repository = repository;
        this.freezeService = freezeService;
        this.readinessService = readinessService;
    }

    @Scheduled(cron = "${tracker.snapshot-cron:0 30 0 * * MON}")
    @SchedulerLock(name = "tracker-snapshot", lockAtLeastFor = "PT1M")
    @Transactional
    public void runOnce() {
        snapshotNow();
    }

    @Transactional
    public void snapshotNow() {
        Params params = Params.defaults();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        double nowYear = toRealYear(today);
        double logitTarget = LogitEta.logitClipped(TARGET_READINESS, params.epsilon());
        List<NodeRow> nodes = repository.findAllNodes();
        ReadinessResult readiness = readinessService.calculate(nodes, params, today);

        List<PillarFit> fits = new ArrayList<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            double effectivePillar = readiness.effectivePillarReadiness()
                    .getOrDefault(pillar, 0.0);
            double rawPillar = readiness.rawPillarReadiness()
                    .getOrDefault(pillar, 0.0);
            PillarFit fit = fitPillar(
                    pillar, effectivePillar, params, today, nowYear, logitTarget);
            fits.add(fit);
            repository.replaceSnapshot(new SnapshotRow(
                    0, pillar, today, fit.readiness(),
                    LogitEta.logitClipped(fit.readiness(), params.epsilon()),
                    fit.slope(), fit.slope(), null, params.windowFixedYears(),
                    fit.etaYear(), fit.etaLow(), fit.etaHigh(), null, params.version(),
                    rawPillar, readiness.graphVersion()));
        }

        double overallReadiness = fits.stream().mapToDouble(PillarFit::readiness).min().orElse(0);
        double overallRawReadiness = readiness.rawPillarReadiness().values().stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0);
        // The overall ETA is the latest pillar ETA; any unresolved pillar makes
        // the overall ETA unknown (rendered as "beyond the clamp horizon").
        PillarFit bottleneck = null;
        if (fits.stream().allMatch(fit -> fit.etaYear() != null)) {
            bottleneck = fits.get(0);
            for (PillarFit fit : fits) {
                if (fit.etaYear() > bottleneck.etaYear()) {
                    bottleneck = fit;
                }
            }
        }
        Double overallEta = bottleneck == null ? null : bottleneck.etaYear();
        Double displayed = dampAgainstOpsState(overallEta);
        repository.replaceSnapshot(new SnapshotRow(
                0, 0, today, overallReadiness,
                LogitEta.logitClipped(overallReadiness, params.epsilon()),
                null, null, null, params.windowFixedYears(),
                overallEta,
                bottleneck == null ? null : bottleneck.etaLow(),
                bottleneck == null ? null : bottleneck.etaHigh(),
                displayed, params.version(), overallRawReadiness,
                readiness.graphVersion()));
        log.info("tracker snapshot for {}: overall readiness {}, eta {}", today, overallReadiness, overallEta);
    }

    static double dampDisplayed(double previous, double computed, double elapsedDays) {
        double allowanceYears = (DAMPING_DAYS_PER_DAY / 365.25) * Math.max(1.0, elapsedDays);
        double delta = computed - previous;
        return previous + Math.max(-allowanceYears, Math.min(allowanceYears, delta));
    }

    private PillarFit fitPillar(
            int pillar, double readiness, Params params,
            LocalDate today, double nowYear, double logitTarget) {
        double logitNow = LogitEta.logitClipped(readiness, params.epsilon());

        List<SnapshotRow> history = repository.findPillarSnapshots(pillar);
        double[] years = new double[history.size() + 1];
        double[] logits = new double[history.size() + 1];
        int i = 0;
        for (SnapshotRow row : history) {
            if (row.snapshotDate().equals(today)) {
                continue;
            }
            years[i] = toRealYear(row.snapshotDate());
            logits[i] = row.logitClipped();
            i++;
        }
        years[i] = nowYear;
        logits[i] = logitNow;
        int observations = i + 1;
        if (observations < 2) {
            return new PillarFit(readiness, null, null, null, null);
        }

        Trend trend;
        try {
            trend = LogitEta.fitWeightedTrend(
                    java.util.Arrays.copyOf(years, observations),
                    java.util.Arrays.copyOf(logits, observations),
                    params.windowFixedYears());
        } catch (IllegalArgumentException tooFewInWindow) {
            return new PillarFit(readiness, null, null, null, null);
        }
        Double eta = LogitEta.etaYear(nowYear, logitNow, trend, logitTarget, params);
        if (eta == null) {
            return new PillarFit(readiness, trend.slopePerYear(), null, null, null);
        }
        double halfWidth = Z_80_PERCENT * trend.residualSe() / trend.slopePerYear();
        double low = Math.max(nowYear + params.etaClampMinYears(), eta - halfWidth);
        double high = Math.min(nowYear + params.etaClampMaxYears(), eta + halfWidth);
        return new PillarFit(readiness, trend.slopePerYear(), eta, Math.min(low, eta), Math.max(high, eta));
    }

    private Double dampAgainstOpsState(Double computedEta) {
        OpsState previous = repository.findOpsState(DISPLAYED_ETA_KEY).orElse(null);
        if (freezeService.isFrozen()) {
            return previous == null ? null : Double.parseDouble(previous.value());
        }
        if (computedEta == null) {
            return null;
        }
        double displayed = computedEta;
        if (previous != null) {
            double elapsedDays = Duration.between(previous.updatedAt(), Instant.now()).toMillis()
                    / (double) Duration.ofDays(1).toMillis();
            displayed = dampDisplayed(Double.parseDouble(previous.value()), computedEta, elapsedDays);
        }
        repository.putOpsState(DISPLAYED_ETA_KEY, String.valueOf(displayed));
        return displayed;
    }

    private static double toRealYear(LocalDate date) {
        return Year.of(date.getYear()).getValue() + (date.getDayOfYear() - 1) / 365.25;
    }
}
