package com.aienterprise.backend.tracker.evaluate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.ops.StateFreezeService;
import com.aienterprise.backend.tracker.ops.StateFreezeService.Trigger;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class GoldenSetJob {

    public static final String LAST_RUN_STATUS_KEY = "GOLDEN_LAST_RUN_STATUS";
    public static final String LAST_LIVE_SUCCESS_KEY = "GOLDEN_LAST_LIVE_SUCCESS";
    public static final String LIVE_ACTIVATED_KEY = "GOLDEN_LIVE_ACTIVATED";
    public static final String BASELINE_REQUIRED_KEY = "GOLDEN_BASELINE_REQUIRED";

    private static final int MAX_BATCH_SIZE = 60;
    private static final double MIN_LIVE_AGREEMENT = 0.90;

    private final GoldenSetEvaluator evaluator;
    private final TrackerRepository repository;
    private final StateFreezeService freezeService;
    private final boolean liveEnabled;
    private final boolean apiConfigured;
    private final GoldenSetEvaluator.VersionTuple versions;
    private final Clock clock;

    @Autowired
    public GoldenSetJob(
            GoldenSetEvaluator evaluator,
            TrackerRepository repository,
            StateFreezeService freezeService,
            @Value("${tracker.golden-live-enabled:false}") boolean liveEnabled,
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${tracker.golden-dataset-version:golden-v1}") String datasetVersion,
            @Value("${tracker.golden-prompt-version:classify-prompt-v1}") String promptVersion,
            @Value("${tracker.golden-model-version:${tracker.classify-model:claude-opus-4-8}}")
            String modelVersion,
            @Value("${tracker.golden-rubric-version:r2.0}") String rubricVersion,
            @Value("${tracker.golden-schema-version:golden-output-v1}") String schemaVersion) {
        this(evaluator, repository, freezeService, liveEnabled,
                apiKey != null && !apiKey.isBlank(),
                new GoldenSetEvaluator.VersionTuple(
                        datasetVersion, promptVersion, modelVersion,
                        rubricVersion, schemaVersion),
                Clock.systemUTC());
    }

    GoldenSetJob(
            GoldenSetEvaluator evaluator,
            TrackerRepository repository,
            StateFreezeService freezeService,
            boolean liveEnabled,
            boolean apiConfigured,
            GoldenSetEvaluator.VersionTuple versions,
            Clock clock) {
        this.evaluator = evaluator;
        this.repository = repository;
        this.freezeService = freezeService;
        this.liveEnabled = liveEnabled;
        this.apiConfigured = apiConfigured;
        this.versions = versions;
        this.clock = clock;
    }

    @Scheduled(cron = "${tracker.golden-cron:0 0 2 * * SUN}", zone = "UTC")
    @SchedulerLock(name = "tracker-golden-set", lockAtLeastFor = "PT1M")
    public void runWeekly() {
        if (!liveEnabled) {
            repository.putOpsState(LAST_RUN_STATUS_KEY, "SKIPPED_DISABLED");
            return;
        }
        if (!apiConfigured) {
            repository.putOpsState(LAST_RUN_STATUS_KEY, "SKIPPED_API_UNAVAILABLE");
            return;
        }
        runForMode(GoldenSetEvaluator.RunMode.LIVE_MODEL);
    }

    public GoldenSetEvaluator.Report runForMode(GoldenSetEvaluator.RunMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("golden run mode is required");
        }
        if (mode == GoldenSetEvaluator.RunMode.LIVE_MODEL
                && (!liveEnabled || !apiConfigured)) {
            throw new IllegalStateException("live golden evaluation is not enabled");
        }

        String fingerprint = tupleFingerprint(versions);
        if (mode == GoldenSetEvaluator.RunMode.LIVE_MODEL
                && repository.findOpsState(LIVE_ACTIVATED_KEY)
                        .map(state -> !fingerprint.equals(state.value()))
                        .orElse(true)) {
            repository.putOpsState(BASELINE_REQUIRED_KEY, "true");
        }

        GoldenSetEvaluator.Report report;
        try {
            report = evaluator.evaluate(mode, versions);
            validateReport(report, mode);
        } catch (RuntimeException failure) {
            if (mode == GoldenSetEvaluator.RunMode.LIVE_MODEL) {
                repository.putOpsState(BASELINE_REQUIRED_KEY, "true");
            }
            repository.putOpsState(LAST_RUN_STATUS_KEY, mode + ":FAILED");
            throw failure;
        }

        repository.putOpsState(LAST_RUN_STATUS_KEY, statusValue(report));
        freezeOnUnsafeAgreement(report);
        if (mode == GoldenSetEvaluator.RunMode.LIVE_MODEL) {
            updateLiveState(report, fingerprint);
        }
        return report;
    }

    private void freezeOnUnsafeAgreement(GoldenSetEvaluator.Report report) {
        if (report.agreement() >= MIN_LIVE_AGREEMENT
                || report.mode() == GoldenSetEvaluator.RunMode.OFFLINE_REPLAY) {
            return;
        }
        Trigger trigger = report.mode() == GoldenSetEvaluator.RunMode.DRILL
                ? Trigger.DRILL : Trigger.AUTOMATIC;
        freezeService.freeze(
                "golden agreement below 0.90: "
                        + report.matchedCount() + "/" + report.totalCount()
                        + " (" + report.mode() + ")",
                trigger);
    }

    private void updateLiveState(
            GoldenSetEvaluator.Report report,
            String fingerprint) {
        boolean completed = "SUCCEEDED".equals(report.status());
        if (completed) {
            repository.putOpsState(
                    LAST_LIVE_SUCCESS_KEY, clock.instant().toString());
        }
        if (completed && report.agreement() >= MIN_LIVE_AGREEMENT) {
            repository.putOpsState(LIVE_ACTIVATED_KEY, fingerprint);
            repository.putOpsState(BASELINE_REQUIRED_KEY, "false");
        } else {
            repository.putOpsState(BASELINE_REQUIRED_KEY, "true");
        }
    }

    private void validateReport(
            GoldenSetEvaluator.Report report,
            GoldenSetEvaluator.RunMode requestedMode) {
        if (report == null
                || report.mode() != requestedMode
                || !versions.equals(report.versions())) {
            throw new IllegalStateException("golden evaluator returned a mismatched report");
        }
        if (report.totalCount() < 1 || report.totalCount() > MAX_BATCH_SIZE) {
            throw new IllegalStateException("golden report exceeds batch limit");
        }
    }

    private static String statusValue(GoldenSetEvaluator.Report report) {
        return report.mode() + ":" + report.status() + ":"
                + report.matchedCount() + "/" + report.totalCount();
    }

    static String tupleFingerprint(GoldenSetEvaluator.VersionTuple tuple) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {
                    tuple.datasetVersion(), tuple.promptVersion(), tuple.modelVersion(),
                    tuple.rubricVersion(), tuple.expectedSchemaVersion() }) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
