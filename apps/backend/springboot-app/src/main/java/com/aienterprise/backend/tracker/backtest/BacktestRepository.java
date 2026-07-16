package com.aienterprise.backend.tracker.backtest;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Atomic persistence boundary for immutable WP4.4 backtest reports. */
@Repository
public class BacktestRepository {

    private static final String RUN_SELECT = """
            SELECT id, input_sha256, report_json, report_sha256,
                   started_at, completed_at
              FROM backtest_run
            """;

    private final JdbcClient jdbc;
    private final BacktestReportCodec codec;

    @Autowired
    public BacktestRepository(JdbcClient jdbc) {
        this(jdbc, new BacktestReportCodec());
    }

    BacktestRepository(JdbcClient jdbc, BacktestReportCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public Optional<StoredRun> findCompletedByInputHash(String inputSha256) {
        return jdbc.sql(RUN_SELECT + """
                 WHERE input_sha256 = :inputHash
                   AND run_status = 'COMPLETED'
                """)
                .param("inputHash", inputSha256)
                .query(BacktestRepository::mapRun)
                .optional()
                .map(this::hydrate);
    }

    public Optional<StoredRun> findCurrent() {
        return jdbc.sql(RUN_SELECT + """
                 WHERE run_status = 'COMPLETED'
                   AND current_result = 'Y'
                 ORDER BY completed_at DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .query(BacktestRepository::mapRun)
                .optional()
                .map(this::hydrate);
    }

    @Transactional
    public StoredRun saveCompleted(BacktestReport report) {
        validateCompleteReport(report);
        Optional<StoredRun> existing = findCompletedByInputHash(
                report.inputSha256());
        if (existing.isPresent()) {
            return existing.get();
        }
        int collision = jdbc.sql("""
                SELECT COUNT(*) FROM backtest_run
                 WHERE input_sha256 = :inputHash
                """)
                .param("inputHash", report.inputSha256())
                .query(Integer.class)
                .single();
        if (collision != 0) {
            throw new IllegalStateException(
                    "backtest input hash belongs to a non-completed run");
        }

        BacktestReportCodec.Encoded encoded = codec.encode(report);
        BacktestCandidate selected = report.selectedCandidate();
        jdbc.sql("""
                INSERT INTO backtest_run
                  (input_sha256, dataset_sha256, node_set_version,
                   rubric_version, params_version, graph_version,
                   candidate_registry_version, calibration_start,
                   calibration_end, holdout_start, holdout_end,
                   horizon_weeks, sample_count, selected_window_m,
                   selected_k_shrink, selected_delta_scale, objective_score,
                   run_status, diagnostics, current_result)
                VALUES
                  (:inputHash, :datasetHash, :nodeSetVersion,
                   :rubricVersion, :paramsVersion, :graphVersion,
                   :candidateRegistry, :calibrationStart,
                   :calibrationEnd, :holdoutStart, :holdoutEnd,
                   :horizonWeeks, :sampleCount, :windowM,
                   :kShrink, :deltaScale, :objectiveScore,
                   'RUNNING', :diagnostics, 'N')
                """)
                .param("inputHash", report.inputSha256())
                .param("datasetHash", report.datasetSha256())
                .param("nodeSetVersion", report.nodeSetVersion())
                .param("rubricVersion", report.rubricVersion())
                .param("paramsVersion", report.paramsVersion())
                .param("graphVersion", report.graphVersion())
                .param("candidateRegistry", report.candidateRegistryVersion())
                .param("calibrationStart", date(report.calibrationStart()))
                .param("calibrationEnd", date(report.calibrationEnd()))
                .param("holdoutStart", date(report.holdoutStart()))
                .param("holdoutEnd", date(report.holdoutEnd()))
                .param("horizonWeeks", report.horizonWeeks())
                .param("sampleCount", report.sampleCount())
                .param("windowM", selected.windowM())
                .param("kShrink", selected.kShrink())
                .param("deltaScale", selected.deltaScale())
                .param("objectiveScore", report.objectiveScore())
                .param("diagnostics", diagnostics(report))
                .update();
        long runId = jdbc.sql("""
                SELECT id FROM backtest_run WHERE input_sha256 = :inputHash
                """)
                .param("inputHash", report.inputSha256())
                .query(Long.class)
                .single();

        report.folds().forEach(fold -> insertFold(runId, fold));
        report.metrics().forEach(metric -> insertMetric(runId, metric));
        requirePersistedCounts(runId, report);

        jdbc.sql("""
                UPDATE backtest_run
                   SET current_result = 'N'
                 WHERE current_result = 'Y'
                """).update();
        int completed = jdbc.sql("""
                UPDATE backtest_run
                   SET run_status = 'COMPLETED',
                       report_json = :reportJson,
                       report_sha256 = :reportHash,
                       current_result = 'Y',
                       completed_at = CURRENT_TIMESTAMP
                 WHERE id = :runId
                   AND run_status = 'RUNNING'
                   AND current_result = 'N'
                """)
                .param("reportJson", encoded.json(), Types.CLOB)
                .param("reportHash", encoded.sha256())
                .param("runId", runId)
                .update();
        if (completed != 1) {
            throw new IllegalStateException(
                    "backtest run completion was not atomic");
        }
        return findCompletedByInputHash(report.inputSha256()).orElseThrow(
                () -> new IllegalStateException(
                        "completed backtest report disappeared"));
    }

    private void insertFold(long runId, BacktestReport.FoldResult fold) {
        jdbc.sql("""
                INSERT INTO backtest_fold
                  (run_id, fold_index, cohort, cutoff_date, target_date,
                   pillar, current_readiness, predicted_readiness,
                   actual_readiness, predicted_logit, actual_logit,
                   predicted_advance, actual_advance, interval_p10,
                   interval_p90, covered, eta_year, fold_status)
                VALUES
                  (:runId, :foldIndex, :cohort, :cutoffDate, :targetDate,
                   :pillar, :currentReadiness, :predictedReadiness,
                   :actualReadiness, :predictedLogit, :actualLogit,
                   :predictedAdvance, :actualAdvance, :intervalP10,
                   :intervalP90, :covered, :etaYear, :foldStatus)
                """)
                .param("runId", runId)
                .param("foldIndex", fold.foldIndex())
                .param("cohort", fold.cohort().name())
                .param("cutoffDate", date(fold.cutoff()))
                .param("targetDate", date(fold.target()))
                .param("pillar", fold.pillar())
                .param("currentReadiness", fold.currentReadiness(), Types.NUMERIC)
                .param("predictedReadiness", fold.predictedReadiness(), Types.NUMERIC)
                .param("actualReadiness", fold.actualReadiness(), Types.NUMERIC)
                .param("predictedLogit", fold.predictedLogit(), Types.NUMERIC)
                .param("actualLogit", fold.actualLogit(), Types.NUMERIC)
                .param("predictedAdvance", yesNo(fold.predictedAdvance()), Types.CHAR)
                .param("actualAdvance", yesNo(fold.actualAdvance()), Types.CHAR)
                .param("intervalP10", fold.intervalP10(), Types.NUMERIC)
                .param("intervalP90", fold.intervalP90(), Types.NUMERIC)
                .param("covered", yesNo(fold.covered()), Types.CHAR)
                .param("etaYear", fold.etaYear(), Types.NUMERIC)
                .param("foldStatus", fold.status().name())
                .update();
    }

    private void insertMetric(
            long runId, BacktestReport.MetricComparison metric) {
        jdbc.sql("""
                INSERT INTO backtest_metric
                  (run_id, metric_code, pillar, calibration_value,
                   holdout_value, calibration_samples, holdout_samples,
                   calibration_status, holdout_status)
                VALUES
                  (:runId, :metricCode, :pillar, :calibrationValue,
                   :holdoutValue, :calibrationSamples, :holdoutSamples,
                   :calibrationStatus, :holdoutStatus)
                """)
                .param("runId", runId)
                .param("metricCode", metric.code().name())
                .param("pillar", metric.pillar())
                .param("calibrationValue", metric.calibrationValue(), Types.NUMERIC)
                .param("holdoutValue", metric.holdoutValue(), Types.NUMERIC)
                .param("calibrationSamples", metric.calibrationSamples())
                .param("holdoutSamples", metric.holdoutSamples())
                .param("calibrationStatus", metric.calibrationStatus().name())
                .param("holdoutStatus", metric.holdoutStatus().name())
                .update();
    }

    private StoredRun hydrate(RunRow row) {
        BacktestReport report = codec.decode(row.reportJson(), row.reportSha256());
        if (!row.inputSha256().equals(report.inputSha256())) {
            throw new IllegalStateException(
                    "backtest report does not match its run input");
        }
        requirePersistedCounts(row.id(), report);
        return new StoredRun(
                row.id(), report, row.reportJson(), row.reportSha256(),
                row.startedAt(), row.completedAt());
    }

    private void requirePersistedCounts(long runId, BacktestReport report) {
        int foldCount = jdbc.sql("""
                SELECT COUNT(*) FROM backtest_fold WHERE run_id = :runId
                """).param("runId", runId).query(Integer.class).single();
        int metricCount = jdbc.sql("""
                SELECT COUNT(*) FROM backtest_metric WHERE run_id = :runId
                """).param("runId", runId).query(Integer.class).single();
        if (foldCount != report.folds().size()
                || metricCount != report.metrics().size()) {
            throw new IllegalStateException(
                    "persisted backtest audit is incomplete");
        }
    }

    private static void validateCompleteReport(BacktestReport report) {
        if (report == null) {
            throw new IllegalArgumentException("backtest report is required");
        }
        Set<BacktestCandidate> expectedCandidates = Set.copyOf(
                BacktestCandidate.registry());
        Set<BacktestCandidate> actualCandidates = new HashSet<>();
        report.calibrationCandidates().forEach(
                score -> actualCandidates.add(score.candidate()));
        int expectedFoldCount = (report.calibrationCutoffCount()
                + report.holdoutCutoffCount()) * 6;
        Set<String> foldKeys = new HashSet<>();
        report.folds().forEach(fold -> foldKeys.add(
                fold.foldIndex() + ":" + fold.pillar()));
        Set<String> metricKeys = new HashSet<>();
        report.metrics().forEach(metric -> metricKeys.add(
                metric.code().name() + ":" + metric.pillar()));
        long calibrationFolds = report.folds().stream()
                .filter(fold -> fold.cohort()
                        == BacktestSchedule.Cohort.CALIBRATION)
                .count();
        long holdoutFolds = report.folds().stream()
                .filter(fold -> fold.cohort()
                        == BacktestSchedule.Cohort.HOLDOUT)
                .count();
        boolean invalid = !BacktestReport.REPORT_VERSION.equals(
                        report.reportVersion())
                || !BacktestCandidate.REGISTRY_VERSION.equals(
                        report.candidateRegistryVersion())
                || !expectedCandidates.equals(actualCandidates)
                || !expectedCandidates.contains(report.selectedCandidate())
                || report.calibrationCandidates().size()
                        != expectedCandidates.size()
                || report.horizonWeeks() != BacktestSchedule.HORIZON_WEEKS
                || report.sampleCount() < 1_000
                || report.sampleCount() > 10_000
                || report.folds().size() != expectedFoldCount
                || foldKeys.size() != expectedFoldCount
                || calibrationFolds != report.calibrationCutoffCount() * 6L
                || holdoutFolds != report.holdoutCutoffCount() * 6L
                || report.metrics().size() != 35
                || metricKeys.size() != 35;
        if (invalid) {
            throw new IllegalArgumentException(
                    "backtest report is not a complete locked split");
        }
    }

    private static RunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunRow(
                rs.getLong("id"),
                rs.getString("input_sha256").trim(),
                rs.getString("report_json"),
                rs.getString("report_sha256").trim(),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant());
    }

    private static Date date(java.time.LocalDate value) {
        return Date.valueOf(value);
    }

    private static String yesNo(Boolean value) {
        return value == null ? null : value ? "Y" : "N";
    }

    private static String diagnostics(BacktestReport report) {
        return "report=" + report.reportVersion()
                + ";candidates=" + report.calibrationCandidates().size()
                + ";folds=" + report.folds().size()
                + ";metrics=" + report.metrics().size();
    }

    public record StoredRun(
            long id,
            BacktestReport report,
            String reportJson,
            String reportSha256,
            Instant startedAt,
            Instant completedAt) {
    }

    private record RunRow(
            long id,
            String inputSha256,
            String reportJson,
            String reportSha256,
            Instant startedAt,
            Instant completedAt) {
    }
}
