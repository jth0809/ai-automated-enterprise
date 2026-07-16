package com.aienterprise.backend.tracker.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aienterprise.backend.tracker.prediction.PredictionCalibrationService;
import com.aienterprise.backend.tracker.prediction.PredictionIssuanceService;
import com.aienterprise.backend.tracker.prediction.PredictionRepository;
import com.aienterprise.backend.tracker.prediction.PredictionResolutionService;

/** Token-gated controls; automatic prediction jobs remain independently dark. */
@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker/admin/predictions")
public class PredictionAdminController {

    private final PredictionIssuanceService issuance;
    private final PredictionResolutionService resolution;
    private final PredictionCalibrationService calibration;
    private final PredictionRepository repository;
    private final String adminToken;
    private final boolean automaticIssuanceEnabled;
    private final boolean automaticResolutionEnabled;
    private final Clock clock;

    @Autowired
    public PredictionAdminController(
            PredictionIssuanceService issuance,
            PredictionResolutionService resolution,
            PredictionCalibrationService calibration,
            PredictionRepository repository,
            @Value("${tracker.admin-token:}") String adminToken,
            @Value("${tracker.phase4-prediction-issuance-enabled:false}")
            boolean automaticIssuanceEnabled,
            @Value("${tracker.phase4-prediction-resolution-enabled:false}")
            boolean automaticResolutionEnabled) {
        this(issuance, resolution, calibration, repository, adminToken,
                automaticIssuanceEnabled, automaticResolutionEnabled,
                Clock.systemUTC());
    }

    PredictionAdminController(
            PredictionIssuanceService issuance,
            PredictionResolutionService resolution,
            PredictionCalibrationService calibration,
            PredictionRepository repository,
            String adminToken,
            boolean automaticIssuanceEnabled,
            boolean automaticResolutionEnabled,
            Clock clock) {
        this.issuance = Objects.requireNonNull(issuance, "issuance");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.calibration = Objects.requireNonNull(calibration, "calibration");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.adminToken = Objects.requireNonNull(adminToken, "adminToken");
        this.automaticIssuanceEnabled = automaticIssuanceEnabled;
        this.automaticResolutionEnabled = automaticResolutionEnabled;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/issue")
    public ResponseEntity<IssueResponse> issue(
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false)
            String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            PredictionRepository.StoredCohort cohort = issuance.issue();
            return ResponseEntity.ok(new IssueResponse(
                    cohort.id(), cohort.cohortKey(), cohort.issuedOn(),
                    cohort.predictions().size(), cohort.inputSha256(),
                    cohort.calibrationVersion()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/resolve")
    public ResponseEntity<PredictionResolutionService.Summary> resolve(
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false)
            String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        PredictionResolutionService.Summary summary = resolution.resolveDue();
        if (summary.conflicts() > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(summary);
        }
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/calibrate")
    public ResponseEntity<CalibrationResponse> calibrate(
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false)
            String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            PredictionCalibrationService.Result result = calibration.calibrate();
            var stored = result.calibration();
            return ResponseEntity.ok(new CalibrationResponse(
                    stored.calibrationVersion(), stored.method(), stored.status(),
                    stored.sampleCount(), stored.quarterCount(), result.reused(),
                    result.issuanceFrozen(), result.alerts().size(),
                    stored.inputSha256()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status(
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false)
            String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new StatusResponse(
                automaticIssuanceEnabled, automaticResolutionEnabled,
                repository.operationsStatus(LocalDate.now(clock))));
    }

    @PostMapping("/{predictionId}/void")
    public ResponseEntity<PredictionRepository.ResolutionResult> voidPrediction(
            @PathVariable("predictionId") long predictionId,
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false)
            String token,
            @RequestBody(required = false) VoidRequest body) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body == null || body.auditNote() == null
                || body.auditNote().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            PredictionRepository.ResolutionResult result =
                    resolution.voidUnadjudicable(
                            predictionId, body.auditNote().trim());
            if (result.status()
                    == PredictionRepository.ResolutionStatus.CONFLICT) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    private boolean authorized(String token) {
        if (adminToken.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    public record VoidRequest(String auditNote) {
    }

    public record IssueResponse(
            long cohortId,
            String cohortKey,
            LocalDate issuedOn,
            int predictionCount,
            String inputSha256,
            String calibrationVersion) {
    }

    public record CalibrationResponse(
            String calibrationVersion,
            PredictionRepository.CalibrationMethod method,
            PredictionRepository.CalibrationStatus status,
            int sampleCount,
            int quarterCount,
            boolean reused,
            boolean issuanceFrozen,
            int openAlertCount,
            String inputSha256) {
    }

    public record StatusResponse(
            boolean automaticIssuanceEnabled,
            boolean automaticResolutionEnabled,
            PredictionRepository.OperationsStatus operations) {
    }
}
