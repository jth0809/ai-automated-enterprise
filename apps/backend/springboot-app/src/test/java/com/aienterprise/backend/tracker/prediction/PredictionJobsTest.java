package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
class PredictionJobsTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void issuanceInitializesCalibrationBeforePublishing() {
        PredictionCalibrationService calibration =
                mock(PredictionCalibrationService.class);
        PredictionIssuanceService issuance = mock(PredictionIssuanceService.class);

        new PredictionIssuanceJob(calibration, issuance).runWeekly();

        var order = inOrder(calibration, issuance);
        order.verify(calibration).calibrate();
        order.verify(issuance).issue();
    }

    @Test
    void resolutionRecalibratesOnlyWhenAnOutcomeWasAttempted() {
        PredictionResolutionService resolution =
                mock(PredictionResolutionService.class);
        PredictionCalibrationService calibration =
                mock(PredictionCalibrationService.class);
        when(resolution.resolveDue()).thenReturn(
                new PredictionResolutionService.Summary(0, 0, 0, 0, 0));
        PredictionResolutionJob job = new PredictionResolutionJob(
                resolution, calibration);

        job.runDaily();

        verify(calibration, never()).calibrate();
        when(resolution.resolveDue()).thenReturn(
                new PredictionResolutionService.Summary(1, 0, 1, 0, 0));
        job.runDaily();
        verify(calibration).calibrate();
    }

    @Test
    void bothJobsShareOneNonOverlappingLockAndUtcSchedules()
            throws Exception {
        Method issuance = PredictionIssuanceJob.class.getMethod("runWeekly");
        Method resolution = PredictionResolutionJob.class.getMethod("runDaily");

        assertEquals("UTC", issuance.getAnnotation(Scheduled.class).zone());
        assertEquals("UTC", resolution.getAnnotation(Scheduled.class).zone());
        assertEquals("tracker-phase4-prediction-operations",
                issuance.getAnnotation(SchedulerLock.class).name());
        assertEquals("tracker-phase4-prediction-operations",
                resolution.getAnnotation(SchedulerLock.class).name());
        assertEquals("PT30M",
                issuance.getAnnotation(SchedulerLock.class).lockAtMostFor());
        assertEquals("PT30M",
                resolution.getAnnotation(SchedulerLock.class).lockAtMostFor());
    }

    @Test
    void flagsAreTrackerSubordinateAndDarkByDefault() {
        ConditionalOnProperty issuance = PredictionIssuanceJob.class
                .getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty resolution = PredictionResolutionJob.class
                .getAnnotation(ConditionalOnProperty.class);

        assertArrayEquals(new String[] {
                "enabled", "phase4-prediction-issuance-enabled"},
                issuance.name());
        assertArrayEquals(new String[] {
                "enabled", "phase4-prediction-resolution-enabled"},
                resolution.name());
        assertEquals("true", issuance.havingValue());
        assertEquals("true", resolution.havingValue());
        assertEquals("false", environment.getProperty(
                "tracker.phase4-prediction-issuance-enabled"));
        assertEquals("false", environment.getProperty(
                "tracker.phase4-prediction-resolution-enabled"));
        assertFalse(context.containsBean("predictionIssuanceJob"));
        assertFalse(context.containsBean("predictionResolutionJob"));
    }
}
