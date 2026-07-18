package com.aienterprise.backend.tracker.math;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.OpsState;
import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.graph.CapabilityGraph;
import com.aienterprise.backend.tracker.graph.CapabilityReadinessService;
import com.aienterprise.backend.tracker.graph.ReadinessResult;
import com.aienterprise.backend.tracker.ops.StateFreezeService;
import com.aienterprise.backend.tracker.projection.ProjectionResult;
import com.aienterprise.backend.tracker.projection.ProjectionRunResult;
import com.aienterprise.backend.tracker.projection.ProjectionService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class SnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotJob.class);
    private static final String DISPLAYED_ETA_KEY = "LAST_DISPLAYED_ETA";
    private static final double TARGET_READINESS = 0.85;
    private static final double DAMPING_DAYS_PER_DAY = 90.0;

    private final TrackerRepository repository;
    private final StateFreezeService freezeService;
    private final CapabilityReadinessService readinessService;
    private final ModelParameterRepository parameterRepository;
    private final CompleteTrendService trendService;
    private final ProjectionService projectionService;
    private final MomentumService momentumService;

    public SnapshotJob(
            TrackerRepository repository,
            StateFreezeService freezeService,
            CapabilityReadinessService readinessService,
            ModelParameterRepository parameterRepository,
            CompleteTrendService trendService,
            ObjectProvider<ProjectionService> projectionService) {
        this.repository = repository;
        this.freezeService = freezeService;
        this.readinessService = readinessService;
        this.parameterRepository = parameterRepository;
        this.trendService = trendService;
        this.projectionService = projectionService.getIfAvailable();
        this.momentumService = new MomentumService();
    }

    @Scheduled(cron = "${tracker.snapshot-cron:0 30 0 * * MON}")
    @SchedulerLock(name = "tracker-snapshot", lockAtLeastFor = "PT1M")
    @Transactional
    public void runOnce() {
        snapshotNow();
    }

    @Transactional
    public void snapshotNow() {
        ModelParameters model = parameterRepository.loadActive();
        Params params = model.params();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<NodeRow> nodes = repository.findAllNodes();
        CapabilityGraph graph = readinessService.loadActiveGraph(nodes);
        ReadinessResult readiness = readinessService.calculate(
                nodes, graph, params, today);
        CompleteTrendModel.Result completeTrend = trendService.calculate(
                readiness.effectivePillarReadiness(), params, today, TARGET_READINESS);

        List<PillarTrendResult> fits = new ArrayList<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            PillarTrendResult fit = completeTrend.pillars().get(pillar);
            fits.add(fit);
        }

        double overallReadiness = fits.stream()
                .mapToDouble(PillarTrendResult::readiness)
                .min().orElse(0);
        double overallRawReadiness = readiness.rawPillarReadiness().values().stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0);
        // The overall ETA is the latest pillar ETA; any unresolved pillar makes
        // the overall ETA unknown (rendered as "beyond the clamp horizon").
        PillarTrendResult bottleneck = null;
        if (fits.stream().allMatch(fit -> fit.etaYear() != null)) {
            bottleneck = fits.get(0);
            for (PillarTrendResult fit : fits) {
                if (fit.etaYear() > bottleneck.etaYear()) {
                    bottleneck = fit;
                }
            }
        }
        ProjectionRunResult projection = projectionService == null
                ? null
                : projectionService.run(new ProjectionService.State(
                        today, nodes, graph, model, readiness, completeTrend,
                        currentMomentum(today, fits, overallReadiness, params,
                                readiness.graphVersion()),
                        TARGET_READINESS));

        for (PillarTrendResult fit : fits) {
            int pillar = fit.pillar();
            double rawPillar = readiness.rawPillarReadiness()
                    .getOrDefault(pillar, 0.0);
            ProjectionResult projected = projection == null
                    ? null : projection.results().get(pillar);
            repository.replaceSnapshot(new SnapshotRow(
                    0, pillar, today, fit.readiness(),
                    LogitEta.logitClipped(fit.readiness(), params.epsilon()),
                    fit.trendFit(), fit.trendUsed(), fit.eventsInWindow(), fit.windowYears(),
                    projected == null ? fit.etaYear() : projected.etaP50(),
                    projected == null ? fit.etaLow() : projected.etaP10(),
                    projected == null ? fit.etaHigh() : projected.etaP90(),
                    null, params.version(), rawPillar, readiness.graphVersion()));
        }

        ProjectionResult projectedOverall = projection == null
                ? null : projection.results().get(0);
        Double centralOverallEta = bottleneck == null ? null : bottleneck.etaYear();
        Double centralOverallLow = bottleneck == null ? null : bottleneck.etaLow();
        Double centralOverallHigh = bottleneck == null ? null : bottleneck.etaHigh();
        Double overallEta = projectedOverall == null
                ? centralOverallEta
                : projectedOverall.etaP50();
        Double overallLow = projectedOverall == null
                ? centralOverallLow
                : projectedOverall.etaP10();
        Double overallHigh = projectedOverall == null
                ? centralOverallHigh
                : projectedOverall.etaP90();
        Double displayed = dampAgainstOpsState(overallEta, params);
        repository.replaceSnapshot(new SnapshotRow(
                0, 0, today, overallReadiness,
                LogitEta.logitClipped(overallReadiness, params.epsilon()),
                null, null, null, params.windowFixedYears(),
                overallEta,
                overallLow,
                overallHigh,
                displayed, params.version(), overallRawReadiness,
                readiness.graphVersion()));
        log.info("tracker snapshot for {}: overall readiness {}, eta {}, projection {}",
                today, overallReadiness, overallEta,
                projection == null ? "OFF" : projection.inputSha256());
    }

    private Map<Integer, MomentumService.Status> currentMomentum(
            LocalDate today,
            List<PillarTrendResult> fits,
            double overallReadiness,
            Params params,
            String graphVersion) {
        Map<Integer, Double> current = new LinkedHashMap<>();
        current.put(0, overallReadiness);
        fits.forEach(fit -> current.put(fit.pillar(), fit.readiness()));

        Map<Integer, MomentumService.Status> result = new LinkedHashMap<>();
        for (int pillar = 0; pillar <= 6; pillar++) {
            double readiness = current.get(pillar);
            List<SnapshotRow> history = new ArrayList<>(
                    repository.findPillarSnapshots(pillar));
            history.add(new SnapshotRow(
                    Long.MAX_VALUE, pillar, today, readiness,
                    LogitEta.logitClipped(readiness, params.epsilon()),
                    null, null, null, params.windowFixedYears(),
                    null, null, null, null, params.version(),
                    readiness, graphVersion));
            result.put(pillar, momentumService.classify(history, today));
        }
        return result;
    }

    static double dampDisplayed(double previous, double computed, double elapsedDays) {
        return dampDisplayed(
                previous, computed, elapsedDays, DAMPING_DAYS_PER_DAY);
    }

    static double dampDisplayed(
            double previous, double computed, double elapsedDays,
            double dampingDaysPerDay) {
        if (!Double.isFinite(dampingDaysPerDay) || dampingDaysPerDay <= 0) {
            throw new IllegalArgumentException("dampingDaysPerDay must be positive");
        }
        double allowanceYears = (dampingDaysPerDay / 365.25)
                * Math.max(1.0, elapsedDays);
        double delta = computed - previous;
        return previous + Math.max(-allowanceYears, Math.min(allowanceYears, delta));
    }

    private Double dampAgainstOpsState(Double computedEta, Params params) {
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
            displayed = dampDisplayed(
                    Double.parseDouble(previous.value()), computedEta,
                    elapsedDays, params.displayDampingDaysPerDay());
        }
        repository.putOpsState(DISPLAYED_ETA_KEY, String.valueOf(displayed));
        return displayed;
    }

}
