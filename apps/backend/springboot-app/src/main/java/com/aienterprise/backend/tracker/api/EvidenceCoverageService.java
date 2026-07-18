package com.aienterprise.backend.tracker.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Aggregates persisted, approved reference evidence without changing scores. */
@Service
public class EvidenceCoverageService {

    private final JdbcClient jdbc;

    public EvidenceCoverageService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public EvidenceCoverage current() {
        int candidateCount = jdbc.sql("""
                SELECT COALESCE(candidate_record_count, 0)
                  FROM backfill_import
                 ORDER BY imported_at DESC, dataset_version DESC
                 FETCH FIRST 1 ROWS ONLY
                """).query(Integer.class).optional().orElse(0);
        int approvedClaims = scalar("""
                SELECT COUNT(DISTINCT h.backfill_id)
                  FROM historical_evidence h
                 WHERE h.reference_status = 'APPROVED'
                   AND h.fact_review_status = 'APPROVED'
                   AND h.rubric_review_status = 'APPROVED'
                """);
        int distinctCandidates = scalar("""
                SELECT COUNT(DISTINCT h.candidate_id)
                  FROM historical_evidence h
                 WHERE h.reference_status = 'APPROVED'
                   AND h.fact_review_status = 'APPROVED'
                   AND h.rubric_review_status = 'APPROVED'
                """);
        int activeNodes = scalar("""
                SELECT COUNT(*) FROM capability_node
                 WHERE node_status = 'ACTIVE'
                """);
        int directlyMappedActiveNodes = scalar("""
                SELECT COUNT(DISTINCT n.id)
                  FROM capability_node n
                  JOIN event e ON e.node_id = n.id
                  JOIN historical_evidence h ON h.event_id = e.id
                 WHERE n.node_status = 'ACTIVE'
                   AND h.reference_status = 'APPROVED'
                   AND h.fact_review_status = 'APPROVED'
                   AND h.rubric_review_status = 'APPROVED'
                """);
        int singleEvidenceClaims = scalar("""
                SELECT COUNT(*)
                  FROM (
                    SELECT h.backfill_id
                      FROM historical_evidence h
                     WHERE h.reference_status = 'APPROVED'
                       AND h.fact_review_status = 'APPROVED'
                       AND h.rubric_review_status = 'APPROVED'
                     GROUP BY h.backfill_id
                    HAVING COUNT(*) = 1
                  ) single_claim
                """);
        Map<String, Integer> verificationCounts = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT e.verification_level, COUNT(DISTINCT h.backfill_id) claim_count
                  FROM historical_evidence h
                  JOIN event e ON e.id = h.event_id
                 WHERE h.reference_status = 'APPROVED'
                   AND h.fact_review_status = 'APPROVED'
                   AND h.rubric_review_status = 'APPROVED'
                 GROUP BY e.verification_level
                 ORDER BY e.verification_level
                """).query((rs, rowNum) -> new VerificationCount(
                        rs.getString("verification_level"),
                        rs.getInt("claim_count")))
                .list().forEach(value -> verificationCounts.put(
                        value.level(), value.count()));

        return new EvidenceCoverage(
                candidateCount, approvedClaims, distinctCandidates,
                activeNodes, directlyMappedActiveNodes, singleEvidenceClaims,
                verificationCounts);
    }

    private int scalar(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private record VerificationCount(String level, int count) {
    }
}
