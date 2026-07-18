package com.aienterprise.backend.tracker.projection;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.math.MomentumService;

@Repository
public class ProjectionRepository {

    private static final String RUN_SELECT = """
            SELECT id, input_sha256, seed_value, sample_count,
                   params_version, graph_version, node_set_version,
                   dataset_sha256, run_status, invalid_sample_count,
                   diagnostics, current_result, started_at, completed_at
              FROM projection_run
            """;

    private final JdbcClient jdbc;

    public ProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DatasetImport> latestDatasetImport() {
        return jdbc.sql("""
                SELECT dataset_version, dataset_sha256, node_set_version,
                       record_count, imported_at
                  FROM backfill_import
                 ORDER BY imported_at DESC, dataset_version DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .query((rs, rowNum) -> new DatasetImport(
                        rs.getString("dataset_version"),
                        trimmed(rs.getString("dataset_sha256")),
                        rs.getString("node_set_version"),
                        rs.getInt("record_count"),
                        rs.getTimestamp("imported_at").toInstant()))
                .optional();
    }

    public Optional<StoredRun> findCompletedByInputHash(String inputSha256) {
        Optional<RunMetadata> metadata = jdbc.sql(RUN_SELECT + """
                 WHERE input_sha256 = :inputHash
                   AND run_status = 'COMPLETED'
                """)
                .param("inputHash", inputSha256)
                .query(ProjectionRepository::mapMetadata)
                .optional();
        return metadata.map(this::hydrate);
    }

    public Optional<StoredRun> findCurrent() {
        Optional<RunMetadata> metadata = jdbc.sql(RUN_SELECT + """
                 WHERE run_status = 'COMPLETED'
                   AND current_result = 'Y'
                 ORDER BY completed_at DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .query(ProjectionRepository::mapMetadata)
                .optional();
        return metadata.map(this::hydrate);
    }

    @Transactional
    public StoredRun saveCompleted(
            ProjectionInput input,
            ProjectionRunResult output) {
        requireMatchingInput(input, output);
        Optional<StoredRun> existing = findCompletedByInputHash(
                output.inputSha256());
        if (existing.isPresent()) {
            return existing.get();
        }
        int colliding = jdbc.sql("""
                SELECT COUNT(*) FROM projection_run
                 WHERE input_sha256 = :inputHash
                """)
                .param("inputHash", output.inputSha256())
                .query(Integer.class)
                .single();
        if (colliding != 0) {
            throw new IllegalStateException(
                    "projection input hash belongs to a non-completed run");
        }

        jdbc.sql("""
                INSERT INTO projection_run
                  (input_sha256, seed_value, sample_count, params_version,
                   graph_version, node_set_version, dataset_sha256, run_status,
                   invalid_sample_count, diagnostics, current_result)
                VALUES
                  (:inputHash, :seed, :samples, :paramsVersion,
                   :graphVersion, :nodeSetVersion, :datasetHash, 'RUNNING',
                   :invalid, :diagnostics, 'N')
                """)
                .param("inputHash", output.inputSha256())
                .param("seed", output.seed())
                .param("samples", output.requestedSamples())
                .param("paramsVersion", input.model().params().version())
                .param("graphVersion", input.graph().version())
                .param("nodeSetVersion", input.nodeSetVersion())
                .param("datasetHash", input.datasetSha256())
                .param("invalid", output.invalidSamples())
                .param("diagnostics", diagnosticsText(output.diagnostics()))
                .update();
        long runId = jdbc.sql("""
                SELECT id FROM projection_run WHERE input_sha256 = :inputHash
                """)
                .param("inputHash", output.inputSha256())
                .query(Long.class)
                .single();

        new TreeMap<>(output.results()).values().forEach(result -> jdbc.sql("""
                INSERT INTO projection_result
                  (run_id, pillar, readiness, eta_p10, eta_p50, eta_p90,
                   censored_fraction, momentum)
                VALUES
                  (:runId, :pillar, :readiness, :etaP10, :etaP50, :etaP90,
                   :censored, :momentum)
                """)
                .param("runId", runId)
                .param("pillar", result.pillar())
                .param("readiness", result.readiness())
                .param("etaP10", result.etaP10(), Types.NUMERIC)
                .param("etaP50", result.etaP50(), Types.NUMERIC)
                .param("etaP90", result.etaP90(), Types.NUMERIC)
                .param("censored", result.censoredFraction())
                .param("momentum", result.momentum().name())
                .update());

        int resultCount = jdbc.sql("""
                SELECT COUNT(*) FROM projection_result WHERE run_id = :runId
                """)
                .param("runId", runId)
                .query(Integer.class)
                .single();
        if (resultCount != 7) {
            throw new IllegalStateException(
                    "projection run did not persist exactly seven rows");
        }

        jdbc.sql("""
                UPDATE projection_run
                   SET current_result = 'N'
                 WHERE current_result = 'Y'
                """).update();
        int completed = jdbc.sql("""
                UPDATE projection_run
                   SET run_status = 'COMPLETED',
                       current_result = 'Y',
                       completed_at = CURRENT_TIMESTAMP
                 WHERE id = :runId
                   AND run_status = 'RUNNING'
                   AND current_result = 'N'
                """)
                .param("runId", runId)
                .update();
        if (completed != 1) {
            throw new IllegalStateException("projection run completion was not atomic");
        }
        return findCompletedByInputHash(output.inputSha256()).orElseThrow(
                () -> new IllegalStateException("completed projection disappeared"));
    }

    private StoredRun hydrate(RunMetadata metadata) {
        Map<Integer, ProjectionResult> results = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT pillar, readiness, eta_p10, eta_p50, eta_p90,
                       censored_fraction, momentum
                  FROM projection_result
                 WHERE run_id = :runId
                 ORDER BY pillar
                """)
                .param("runId", metadata.id())
                .query((rs, rowNum) -> new ProjectionResult(
                        rs.getInt("pillar"),
                        rs.getDouble("readiness"),
                        nullableDouble(rs, "eta_p10"),
                        nullableDouble(rs, "eta_p50"),
                        nullableDouble(rs, "eta_p90"),
                        rs.getDouble("censored_fraction"),
                        MomentumService.Status.valueOf(rs.getString("momentum"))))
                .list()
                .forEach(result -> results.put(result.pillar(), result));
        ProjectionRunResult output = new ProjectionRunResult(
                metadata.inputSha256(), metadata.seed(), metadata.sampleCount(),
                metadata.sampleCount() - metadata.invalidSamples(),
                metadata.invalidSamples(),
                parseDiagnostics(metadata.diagnostics()), results);
        return new StoredRun(
                metadata.id(), output, metadata.paramsVersion(),
                metadata.graphVersion(), metadata.nodeSetVersion(),
                metadata.datasetSha256(), metadata.startedAt(),
                metadata.completedAt());
    }

    private static RunMetadata mapMetadata(ResultSet rs, int rowNum)
            throws SQLException {
        return new RunMetadata(
                rs.getLong("id"),
                trimmed(rs.getString("input_sha256")),
                rs.getLong("seed_value"),
                rs.getInt("sample_count"),
                rs.getString("params_version"),
                rs.getString("graph_version"),
                rs.getString("node_set_version"),
                trimmed(rs.getString("dataset_sha256")),
                rs.getString("run_status"),
                rs.getInt("invalid_sample_count"),
                rs.getString("diagnostics"),
                rs.getString("current_result"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null
                        ? null : rs.getTimestamp("completed_at").toInstant());
    }

    private static Double nullableDouble(ResultSet rs, String column)
            throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static void requireMatchingInput(
            ProjectionInput input,
            ProjectionRunResult output) {
        if (input == null || output == null) {
            throw new IllegalArgumentException("projection input and output are required");
        }
        ProjectionFingerprint.Value expected = ProjectionFingerprint.of(input);
        if (!expected.sha256().equals(output.inputSha256())
                || expected.seed() != output.seed()
                || input.sampleCount() != output.requestedSamples()) {
            throw new IllegalArgumentException(
                    "projection output does not match its canonical input");
        }
    }

    private static String diagnosticsText(Map<String, Double> diagnostics) {
        StringBuilder value = new StringBuilder();
        new TreeMap<>(diagnostics).forEach((name, number) -> value
                .append(name).append('=')
                .append(Double.toHexString(number)).append('\n'));
        return value.toString();
    }

    private static Map<String, Double> parseDiagnostics(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String line : value.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new IllegalStateException("malformed projection diagnostics");
            }
            result.put(line.substring(0, separator),
                    Double.valueOf(line.substring(separator + 1)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    public record DatasetImport(
            String datasetVersion,
            String datasetSha256,
            String nodeSetVersion,
            int recordCount,
            Instant importedAt) {
    }

    public record StoredRun(
            long id,
            ProjectionRunResult output,
            String paramsVersion,
            String graphVersion,
            String nodeSetVersion,
            String datasetSha256,
            Instant startedAt,
            Instant completedAt) {
    }

    private record RunMetadata(
            long id,
            String inputSha256,
            long seed,
            int sampleCount,
            String paramsVersion,
            String graphVersion,
            String nodeSetVersion,
            String datasetSha256,
            String status,
            int invalidSamples,
            String diagnostics,
            String current,
            Instant startedAt,
            Instant completedAt) {
    }
}
