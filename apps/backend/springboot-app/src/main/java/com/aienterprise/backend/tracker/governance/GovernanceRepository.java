package com.aienterprise.backend.tracker.governance;

import java.sql.Date;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence boundary for reviewed P6 reference facts and import audits. */
@Repository
public class GovernanceRepository {

    private final JdbcClient jdbc;

    public GovernanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ImportRow> findImport(String datasetVersion) {
        return jdbc.sql("""
                SELECT dataset_version, dataset_sha256, record_count, loaded_at
                  FROM governance_import
                 WHERE dataset_version = :version
                """).param("version", datasetVersion)
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
                  FROM governance_import
                 ORDER BY loaded_at DESC, dataset_version DESC
                 FETCH FIRST 1 ROWS ONLY
                """).query((rs, rowNum) -> new ImportRow(
                        rs.getString("dataset_version"),
                        rs.getString("dataset_sha256"),
                        rs.getInt("record_count"),
                        rs.getTimestamp("loaded_at").toInstant()))
                .optional();
    }

    public List<GovernanceRecord> findAll(int requestedLimit) {
        if (requestedLimit <= 0) {
            return List.of();
        }
        int limit = Math.min(50, requestedLimit);
        return jdbc.sql("""
                SELECT g.record_id, g.record_type, g.jurisdiction, g.subject,
                       g.record_status, g.effective_on, g.effective_on_precision,
                       s.code AS source_code, g.source_url, g.accessed_on,
                       g.content_sha256, g.publication_path, g.fact_summary,
                       g.review_status
                  FROM governance_record g
                  JOIN source_registry s ON s.id = g.source_id
                 ORDER BY g.effective_on DESC, g.record_id
                 FETCH FIRST %d ROWS ONLY
                """.formatted(limit))
                .query((rs, rowNum) -> new GovernanceRecord(
                        rs.getString("record_id"),
                        rs.getString("record_type"),
                        rs.getString("jurisdiction"),
                        rs.getString("subject"),
                        rs.getString("record_status"),
                        rs.getDate("effective_on").toLocalDate(),
                        rs.getString("effective_on_precision"),
                        rs.getString("source_code"),
                        rs.getString("source_url"),
                        rs.getDate("accessed_on").toLocalDate(),
                        rs.getString("content_sha256"),
                        rs.getString("publication_path"),
                        rs.getString("fact_summary"),
                        rs.getString("review_status")))
                .list();
    }

    public void upsert(GovernanceRecord record, String datasetVersion) {
        long sourceId = sourceId(record.sourceCode());
        Map<String, Object> params = parameters(record, datasetVersion, sourceId);
        int updated = jdbc.sql("""
                UPDATE governance_record
                   SET record_type = :recordType,
                       jurisdiction = :jurisdiction,
                       subject = :subject,
                       record_status = :status,
                       effective_on = :effectiveOn,
                       effective_on_precision = :precision,
                       source_id = :sourceId,
                       source_url = :sourceUrl,
                       accessed_on = :accessedOn,
                       content_sha256 = :sha,
                       publication_path = :publicationPath,
                       fact_summary = :factSummary,
                       review_status = :reviewStatus,
                       dataset_version = :datasetVersion,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE record_id = :recordId
                """).params(params).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO governance_record
                      (record_id, record_type, jurisdiction, subject, record_status,
                       effective_on, effective_on_precision, source_id, source_url,
                       accessed_on, content_sha256, publication_path, fact_summary,
                       review_status, dataset_version)
                    VALUES
                      (:recordId, :recordType, :jurisdiction, :subject, :status,
                       :effectiveOn, :precision, :sourceId, :sourceUrl,
                       :accessedOn, :sha, :publicationPath, :factSummary,
                       :reviewStatus, :datasetVersion)
                    """).params(params).update();
        }
    }

    public void recordImport(String version, String sha, int recordCount) {
        jdbc.sql("""
                INSERT INTO governance_import
                  (dataset_version, dataset_sha256, record_count)
                VALUES (:version, :sha, :recordCount)
                """).param("version", version)
                .param("sha", sha)
                .param("recordCount", recordCount)
                .update();
    }

    private long sourceId(String sourceCode) {
        return jdbc.sql("SELECT id FROM source_registry WHERE code = :code")
                .param("code", sourceCode).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown governance source: " + sourceCode));
    }

    private static Map<String, Object> parameters(
            GovernanceRecord record, String datasetVersion, long sourceId) {
        return Map.ofEntries(
                Map.entry("recordId", record.recordId()),
                Map.entry("recordType", record.recordType()),
                Map.entry("jurisdiction", record.jurisdiction()),
                Map.entry("subject", record.subject()),
                Map.entry("status", record.status()),
                Map.entry("effectiveOn", Date.valueOf(record.effectiveOn())),
                Map.entry("precision", record.effectiveOnPrecision()),
                Map.entry("sourceId", sourceId),
                Map.entry("sourceUrl", record.sourceUrl()),
                Map.entry("accessedOn", Date.valueOf(record.accessedOn())),
                Map.entry("sha", record.contentSha256()),
                Map.entry("publicationPath", record.publicationPath()),
                Map.entry("factSummary", record.factSummary()),
                Map.entry("reviewStatus", record.reviewStatus()),
                Map.entry("datasetVersion", datasetVersion));
    }

    public record ImportRow(
            String datasetVersion,
            String datasetSha256,
            int recordCount,
            Instant loadedAt) {
    }
}
