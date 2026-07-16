package com.aienterprise.backend.tracker.prediction;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Dark-by-default due-date resolution and probability recalibration. */
@Component
@ConditionalOnProperty(prefix = "tracker",
        name = {"enabled", "phase4-prediction-resolution-enabled"},
        havingValue = "true")
public class PredictionResolutionJob {

    private final PredictionResolutionService resolution;
    private final PredictionCalibrationService calibration;

    @Autowired
    public PredictionResolutionJob(
            PredictionResolutionService resolution,
            PredictionCalibrationService calibration) {
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.calibration = Objects.requireNonNull(calibration, "calibration");
    }

    @Scheduled(
            cron = "${tracker.phase4-prediction-resolution-cron:0 15 2 * * *}",
            zone = "UTC")
    @SchedulerLock(
            name = "tracker-phase4-prediction-operations",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runDaily() {
        PredictionResolutionService.Summary summary = resolution.resolveDue();
        if (summary.attempted() > 0) {
            calibration.calibrate();
        }
    }
}
