package com.aienterprise.backend.tracker.forecast;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence boundary for reviewed references and external forecast history. */
@Repository
public class ForecastRepository {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final String REFERENCE_SELECT = """
            SELECT forecast_key, source_type, source_name, track_code, question,
                   target_definition, display_status, forecast_year,
                   forecast_year_low, forecast_year_high, relation_kind,
                   source_url, source_locator, accessed_on, ingestion_mode,
                   content_sha256, fact_summary
              FROM forecast_reference
            """;

    private final JdbcClient jdbc;

    public ForecastRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ImportRow> findImport(String datasetVersion) {
        return jdbc.sql("""
                SELECT dataset_version, dataset_sha256, record_count, loaded_at
                  FROM forecast_reference_import
                 WHERE dataset_version = :version
                """)
                .param("version", datasetVersion)
                .query((rs, rowNum) -> new ImportRow(
                        rs.getString("dataset_version"),
                        rs.getString("dataset_sha256"),
                        rs.getInt("record_count"),
                        rs.getTimestamp("loaded_at").toInstant()))
                .optional();
    }

    public Optional<ImportRow> latestImport() {
        return jdbc.sql("""
                SELECT dataset_version, dataset_sha256, record_count, loaded_at
                  FROM forecast_reference_import
                 ORDER BY loaded_at DESC, dataset_version DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .query((rs, rowNum) -> new ImportRow(
                        rs.getString("dataset_version"),
                        rs.getString("dataset_sha256"),
                        rs.getInt("record_count"),
                        rs.getTimestamp("loaded_at").toInstant()))
                .optional();
    }

    public Optional<ForecastReference> findReference(String forecastKey) {
        return jdbc.sql(REFERENCE_SELECT + " WHERE forecast_key = :key")
                .param("key", forecastKey)
                .query(ForecastRepository::mapReference)
                .optional();
    }

    public List<ForecastReference> findAllReferences() {
        return jdbc.sql(REFERENCE_SELECT + " ORDER BY track_code, source_type, forecast_key")
                .query(ForecastRepository::mapReference)
                .list();
    }

    public List<ForecastReference> findReferencesByTrack(String trackCode) {
        return jdbc.sql(REFERENCE_SELECT + """
                 WHERE track_code = :track
                 ORDER BY source_type, source_name, forecast_key
                """)
                .param("track", trackCode)
                .query(ForecastRepository::mapReference)
                .list();
    }

    public List<ForecastReference> findCrowdReferences(int requestedLimit) {
        if (requestedLimit <= 0) {
            return List.of();
        }
        int limit = Math.min(2, requestedLimit);
        return jdbc.sql(REFERENCE_SELECT + """
                 WHERE source_type = 'CROWD' AND source_name = 'METACULUS'
                 ORDER BY forecast_key
                 FETCH FIRST %d ROWS ONLY
                """.formatted(limit))
                .query(ForecastRepository::mapReference)
                .list();
    }

    public void upsertReference(ForecastReference value, String datasetVersion) {
        int updated = bind(jdbc.sql("""
                UPDATE forecast_reference
                   SET source_type = :sourceType,
                       source_name = :sourceName,
                       track_code = :trackCode,
                       question = :question,
                       target_definition = :targetDefinition,
                       display_status = :displayStatus,
                       forecast_year = :forecastYear,
                       forecast_year_low = :forecastYearLow,
                       forecast_year_high = :forecastYearHigh,
                       relation_kind = :relationKind,
                       source_url = :sourceUrl,
                       source_locator = :sourceLocator,
                       accessed_on = :accessedOn,
                       ingestion_mode = :ingestionMode,
                       content_sha256 = :contentSha256,
                       fact_summary = :factSummary,
                       dataset_version = :datasetVersion,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE forecast_key = :forecastKey
                """), value, datasetVersion).update();
        if (updated == 0) {
            bind(jdbc.sql("""
                    INSERT INTO forecast_reference
                      (forecast_key, source_type, source_name, track_code, question,
                       target_definition, display_status, forecast_year,
                       forecast_year_low, forecast_year_high, relation_kind,
                       source_url, source_locator, accessed_on, ingestion_mode,
                       content_sha256, fact_summary, dataset_version)
                    VALUES
                      (:forecastKey, :sourceType, :sourceName, :trackCode, :question,
                       :targetDefinition, :displayStatus, :forecastYear,
                       :forecastYearLow, :forecastYearHigh, :relationKind,
                       :sourceUrl, :sourceLocator, :accessedOn, :ingestionMode,
                       :contentSha256, :factSummary, :datasetVersion)
                    """), value, datasetVersion).update();
        }
    }

    public void recordImport(String version, String sha, int recordCount) {
        jdbc.sql("""
                INSERT INTO forecast_reference_import
                  (dataset_version, dataset_sha256, record_count)
                VALUES (:version, :sha, :recordCount)
                """)
                .param("version", version)
                .param("sha", sha)
                .param("recordCount", recordCount)
                .update();
    }

    @Transactional
    public long saveCrowdObservation(
            String forecastKey,
            BigDecimal forecastYear,
            BigDecimal smoothedYear,
            LocalDate retrievedOn,
            String observationSha256) {
        ForecastReference reference = findReference(forecastKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown forecast reference: " + forecastKey));
        if (!"CROWD".equals(reference.sourceType())) {
            throw new IllegalArgumentException(
                    "External observation reference must be CROWD: " + forecastKey);
        }
        validateObservation(forecastYear, smoothedYear, retrievedOn, observationSha256);
        Optional<ExternalForecastObservation> existing = findCrowdObservation(
                forecastKey, retrievedOn);
        if (existing.isPresent()) {
            if (sameDecimal(existing.get().forecastYear(), forecastYear)
                    && sameDecimal(existing.get().smoothedYear(), smoothedYear)
                    && observationSha256.equals(existing.get().observationSha256())) {
                return existing.get().id();
            }
            throw new IllegalStateException(
                    "Conflicting crowd observation for " + forecastKey + " on " + retrievedOn);
        }
        try {
            jdbc.sql("""
                    INSERT INTO external_forecast
                      (source_type, source_name, question, forecast_year,
                       smoothed_year, retrieved_on, forecast_key,
                       observation_sha256, observation_status, smoothing_window_days)
                    VALUES
                      ('CROWD', :sourceName, :question, :forecastYear,
                       :smoothedYear, :retrievedOn, :forecastKey,
                       :sha, 'CURRENT', :windowDays)
                    """)
                    .param("sourceName", reference.sourceName())
                    .param("question", reference.question())
                    .param("forecastYear", forecastYear)
                    .param("smoothedYear", smoothedYear)
                    .param("retrievedOn", Date.valueOf(retrievedOn))
                    .param("forecastKey", forecastKey)
                    .param("sha", observationSha256)
                    .param("windowDays", ForecastSmoother.WINDOW_DAYS)
                    .update();
        } catch (DuplicateKeyException concurrentInsert) {
            Optional<ExternalForecastObservation> concurrent = findCrowdObservation(
                    forecastKey, retrievedOn);
            if (concurrent.isPresent()
                    && sameDecimal(concurrent.get().forecastYear(), forecastYear)
                    && sameDecimal(concurrent.get().smoothedYear(), smoothedYear)
                    && observationSha256.equals(concurrent.get().observationSha256())) {
                return concurrent.get().id();
            }
            throw new IllegalStateException(
                    "Conflicting concurrent crowd observation for " + forecastKey,
                    concurrentInsert);
        }
        return findCrowdObservation(forecastKey, retrievedOn).orElseThrow().id();
    }

    public Optional<ExternalForecastObservation> findLatestCrowdObservation(
            String forecastKey) {
        return jdbc.sql("""
                SELECT id, forecast_key, source_type, source_name, question,
                       forecast_year, smoothed_year, retrieved_on,
                       observation_sha256, observation_status, smoothing_window_days
                 FROM external_forecast
                 WHERE forecast_key = :key
                   AND source_type = 'CROWD'
                   AND observation_status = 'CURRENT'
                 ORDER BY retrieved_on DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .param("key", forecastKey)
                .query(ForecastRepository::mapObservation)
                .optional();
    }

    public List<ExternalForecastObservation> findCrowdWindow(
            String forecastKey,
            LocalDate start,
            LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("A valid forecast window is required");
        }
        return jdbc.sql("""
                SELECT id, forecast_key, source_type, source_name, question,
                       forecast_year, smoothed_year, retrieved_on,
                       observation_sha256, observation_status, smoothing_window_days
                 FROM external_forecast
                 WHERE forecast_key = :key
                   AND source_type = 'CROWD'
                   AND observation_status = 'CURRENT'
                   AND retrieved_on BETWEEN :startDate AND :endDate
                 ORDER BY retrieved_on, id
                """)
                .param("key", forecastKey)
                .param("startDate", Date.valueOf(start))
                .param("endDate", Date.valueOf(end))
                .query(ForecastRepository::mapObservation)
                .list();
    }

    private Optional<ExternalForecastObservation> findCrowdObservation(
            String forecastKey,
            LocalDate retrievedOn) {
        return jdbc.sql("""
                SELECT id, forecast_key, source_type, source_name, question,
                       forecast_year, smoothed_year, retrieved_on,
                       observation_sha256, observation_status, smoothing_window_days
                 FROM external_forecast
                 WHERE forecast_key = :key
                   AND source_type = 'CROWD'
                   AND observation_status = 'CURRENT'
                   AND retrieved_on = :retrievedOn
                """)
                .param("key", forecastKey)
                .param("retrievedOn", Date.valueOf(retrievedOn))
                .query(ForecastRepository::mapObservation)
                .optional();
    }

    private static JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec spec,
            ForecastReference value,
            String datasetVersion) {
        return spec
                .param("forecastKey", value.forecastKey())
                .param("sourceType", value.sourceType())
                .param("sourceName", value.sourceName())
                .param("trackCode", value.trackCode())
                .param("question", value.question())
                .param("targetDefinition", value.targetDefinition())
                .param("displayStatus", value.displayStatus())
                .param("forecastYear", value.forecastYear(), Types.NUMERIC)
                .param("forecastYearLow", value.forecastYearLow(), Types.NUMERIC)
                .param("forecastYearHigh", value.forecastYearHigh(), Types.NUMERIC)
                .param("relationKind", value.relationKind())
                .param("sourceUrl", value.sourceUrl())
                .param("sourceLocator", value.sourceLocator(), Types.VARCHAR)
                .param("accessedOn", Date.valueOf(value.accessedOn()))
                .param("ingestionMode", value.ingestionMode())
                .param("contentSha256", value.contentSha256())
                .param("factSummary", value.factSummary())
                .param("datasetVersion", datasetVersion);
    }

    private static ForecastReference mapReference(ResultSet rs, int rowNum)
            throws SQLException {
        return new ForecastReference(
                rs.getString("forecast_key"),
                rs.getString("source_type"),
                rs.getString("source_name"),
                rs.getString("track_code"),
                rs.getString("question"),
                rs.getString("target_definition"),
                rs.getString("display_status"),
                rs.getBigDecimal("forecast_year"),
                rs.getBigDecimal("forecast_year_low"),
                rs.getBigDecimal("forecast_year_high"),
                rs.getString("relation_kind"),
                rs.getString("source_url"),
                rs.getString("source_locator"),
                rs.getDate("accessed_on").toLocalDate(),
                rs.getString("ingestion_mode"),
                rs.getString("content_sha256"),
                rs.getString("fact_summary"));
    }

    private static ExternalForecastObservation mapObservation(ResultSet rs, int rowNum)
            throws SQLException {
        return new ExternalForecastObservation(
                rs.getLong("id"),
                rs.getString("forecast_key"),
                rs.getString("source_type"),
                rs.getString("source_name"),
                rs.getString("question"),
                rs.getBigDecimal("forecast_year"),
                rs.getBigDecimal("smoothed_year"),
                rs.getDate("retrieved_on").toLocalDate(),
                rs.getString("observation_sha256"),
                rs.getString("observation_status"),
                rs.getInt("smoothing_window_days"));
    }

    private static void validateObservation(
            BigDecimal forecastYear,
            BigDecimal smoothedYear,
            LocalDate retrievedOn,
            String hash) {
        if (forecastYear == null || smoothedYear == null || retrievedOn == null) {
            throw new IllegalArgumentException("A crowd observation requires year, mean, and date");
        }
        BigDecimal min = new BigDecimal("2026.0");
        BigDecimal max = new BigDecimal("2300.0");
        if (forecastYear.compareTo(min) < 0 || forecastYear.compareTo(max) > 0
                || smoothedYear.compareTo(min) < 0 || smoothedYear.compareTo(max) > 0) {
            throw new IllegalArgumentException("Crowd observation years are out of bounds");
        }
        if (forecastYear.stripTrailingZeros().scale() > 1
                || smoothedYear.stripTrailingZeros().scale() > 1) {
            throw new IllegalArgumentException(
                    "Crowd observation years support at most one decimal place");
        }
        if (hash == null || !SHA256.matcher(hash).matches()) {
            throw new IllegalArgumentException("Crowd observation hash must be lowercase SHA-256");
        }
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    public record ImportRow(
            String datasetVersion,
            String datasetSha256,
            int recordCount,
            Instant loadedAt) {
    }
}
