package com.aienterprise.backend.tracker.backtest;

import java.time.LocalDate;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Explicit-only coordinator for deterministic backtest calculation and audit. */
@Service
@ConditionalOnProperty(
        prefix = "tracker",
        name = {"enabled", "phase4-backtest-enabled"},
        havingValue = "true")
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final BacktestRepository repository;
    private final BacktestInputFactory inputs;
    private final Calculator calculator;

    @Autowired
    public BacktestService(
            BacktestRepository repository,
            BacktestInputFactory inputs) {
        this(repository, inputs, new BacktestHarness()::run);
    }

    BacktestService(
            BacktestRepository repository,
            BacktestInputFactory inputs,
            Calculator calculator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    @SchedulerLock(name = "tracker-phase4-backtest", lockAtMostFor = "PT2H")
    public BacktestRepository.StoredRun run() {
        BacktestHarness.Input input = inputs.create();
        BacktestFingerprint.Value fingerprint = BacktestFingerprint.of(
                input.descriptor());
        var existing = repository.findCompletedByInputHash(
                fingerprint.sha256());
        if (existing.isPresent()) {
            requireMatching(input, fingerprint, existing.get().report());
            logSummary("reused", existing.get());
            return existing.get();
        }

        BacktestReport report = calculator.calculate(input);
        requireMatching(input, fingerprint, report);
        BacktestRepository.StoredRun stored = repository.saveCompleted(report);
        logSummary("completed", stored);
        return stored;
    }

    private static void requireMatching(
            BacktestHarness.Input input,
            BacktestFingerprint.Value fingerprint,
            BacktestReport report) {
        BacktestFingerprint.Descriptor descriptor = input.descriptor();
        LocalDate expectedAsOf = descriptor.schedule().all().stream()
                .map(BacktestSchedule.Fold::target)
                .max(LocalDate::compareTo).orElseThrow();
        boolean mismatch = report == null
                || !fingerprint.sha256().equals(report.inputSha256())
                || fingerprint.seed() != report.seed()
                || !descriptor.datasetSha256().equals(report.datasetSha256())
                || !descriptor.nodeSetVersion().equals(report.nodeSetVersion())
                || !descriptor.rubricVersion().equals(report.rubricVersion())
                || !descriptor.paramsVersion().equals(report.paramsVersion())
                || !descriptor.graphVersion().equals(report.graphVersion())
                || !BacktestCandidate.REGISTRY_VERSION.equals(
                        report.candidateRegistryVersion())
                || descriptor.sampleCount() != report.sampleCount()
                || BacktestSchedule.HORIZON_WEEKS != report.horizonWeeks()
                || descriptor.schedule().calibration().folds().size()
                        != report.calibrationCutoffCount()
                || descriptor.schedule().holdout().folds().size()
                        != report.holdoutCutoffCount()
                || !expectedAsOf.equals(report.asOf());
        if (mismatch) {
            throw new IllegalStateException(
                    "backtest calculator returned a report for another input");
        }
    }

    private static void logSummary(
            String action, BacktestRepository.StoredRun stored) {
        BacktestReport report = stored.report();
        BacktestCandidate selected = report.selectedCandidate();
        log.info("tracker backtest {} id={} input={} report={} seed={} "
                        + "selected(m:k:delta)={}:{}:{} objective={} "
                        + "folds={} metrics={}",
                action, stored.id(), report.inputSha256(),
                stored.reportSha256(), report.seed(),
                selected.windowM(), selected.kShrink(), selected.deltaScale(),
                report.objectiveScore(), report.folds().size(),
                report.metrics().size());
    }

    @FunctionalInterface
    interface Calculator {
        BacktestReport calculate(BacktestHarness.Input input);
    }
}
