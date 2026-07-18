package com.aienterprise.backend.tracker.prediction;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Dark-by-default weekly probability publication. */
@Component
@ConditionalOnProperty(prefix = "tracker",
        name = {"enabled", "phase4-prediction-issuance-enabled"},
        havingValue = "true")
public class PredictionIssuanceJob {

    private final PredictionCalibrationService calibration;
    private final PredictionIssuanceService issuance;

    @Autowired
    public PredictionIssuanceJob(
            PredictionCalibrationService calibration,
            PredictionIssuanceService issuance) {
        this.calibration = Objects.requireNonNull(calibration, "calibration");
        this.issuance = Objects.requireNonNull(issuance, "issuance");
    }

    @Scheduled(
            cron = "${tracker.phase4-prediction-issuance-cron:0 45 1 * * MON}",
            zone = "UTC")
    @SchedulerLock(
            name = "tracker-phase4-prediction-operations",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runWeekly() {
        calibration.calibrate();
        issuance.issue();
    }
}
