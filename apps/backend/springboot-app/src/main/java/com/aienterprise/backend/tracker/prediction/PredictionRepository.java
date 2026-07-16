package com.aienterprise.backend.tracker.prediction;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Atomic persistence boundary for immutable micro-prediction cohorts. */
@Repository
public class PredictionRepository {

    private static final String COHORT_SELECT = """
            SELECT id, cohort_key, input_sha256, dataset_sha256,
                   node_set_version, rubric_version, hazard_params_version,
                   calibration_version, as_of_date, issued_on,
                   prediction_count, created_at, completed_at
              FROM prediction_cohort
            """;

    private final JdbcClient jdbc;

    @Autowired
    public PredictionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public HazardParameters loadActiveParameters() {
        return jdbc.sql("""
                SELECT version_label, kappa_node_years, probability_floor,
                       probability_ceiling, horizons_months, cohort_limit,
                       pillar_limit, calibration_min_outcomes,
                       calibration_min_quarters
                 FROM prediction_parameter_set
                 WHERE active = 'Y'
                   AND node_set_version = 'nodes-v1.0'
                   AND rubric_version = 'r2.0'
                """).query((rs, rowNum) -> new HazardParameters(
                        rs.getString("version_label"),
                        rs.getDouble("kappa_node_years"),
                        rs.getDouble("probability_floor"),
                        rs.getDouble("probability_ceiling"),
                        parseHorizons(rs.getString("horizons_months")),
                        rs.getInt("cohort_limit"),
                        rs.getInt("pillar_limit"),
                        rs.getInt("calibration_min_outcomes"),
                        rs.getInt("calibration_min_quarters")))
                .single();
    }

    public Optional<StoredCohort> findCompletedByInputHash(String inputSha256) {
        return jdbc.sql(COHORT_SELECT + """
                 WHERE input_sha256 = :inputHash
                   AND cohort_status = 'COMPLETED'
                """).param("inputHash", inputSha256)
                .query(PredictionRepository::mapCohort)
                .optional().map(this::hydrate);
    }

    @Transactional
    public StoredCohort saveCompleted(CohortDraft draft) {
        Objects.requireNonNull(draft, "draft");
        Optional<StoredCohort> existing = findCompletedByInputHash(
                draft.inputSha256());
        if (existing.isPresent()) {
            if (!existing.get().draftIdentityEquals(draft)) {
                throw new IllegalStateException(
                        "prediction input hash belongs to another cohort");
            }
            return existing.get();
        }
        int collision = jdbc.sql("""
                SELECT COUNT(*) FROM prediction_cohort
                 WHERE input_sha256 = :inputHash OR cohort_key = :cohortKey
                """).param("inputHash", draft.inputSha256())
                .param("cohortKey", draft.cohortKey())
                .query(Integer.class).single();
        if (collision != 0) {
            throw new IllegalStateException("incomplete prediction cohort collision");
        }

        jdbc.sql("""
                INSERT INTO prediction_cohort
                  (cohort_key, input_sha256, dataset_sha256, node_set_version,
                   rubric_version, hazard_params_version, calibration_version,
                   as_of_date, issued_on, prediction_count, cohort_status,
                   diagnostics)
                VALUES
                  (:cohortKey, :inputHash, :datasetHash, :nodeSet,
                   :rubric, :hazard, :calibration, :asOf, :issuedOn,
                   0, 'RUNNING', :diagnostics)
                """)
                .param("cohortKey", draft.cohortKey())
                .param("inputHash", draft.inputSha256())
                .param("datasetHash", draft.datasetSha256())
                .param("nodeSet", draft.nodeSetVersion())
                .param("rubric", draft.rubricVersion())
                .param("hazard", draft.hazardParamsVersion())
                .param("calibration", draft.calibrationVersion())
                .param("asOf", Date.valueOf(draft.asOf()))
                .param("issuedOn", Date.valueOf(draft.issuedOn()))
                .param("diagnostics", diagnostics(draft))
                .update();
        long cohortId = jdbc.sql("""
                SELECT id FROM prediction_cohort WHERE input_sha256 = :inputHash
                """).param("inputHash", draft.inputSha256())
                .query(Long.class).single();
        for (int i = 0; i < draft.predictions().size(); i++) {
            insertPrediction(
                    cohortId, draft, draft.predictions().get(i), i + 1);
        }
        int completed = jdbc.sql("""
                UPDATE prediction_cohort
                   SET prediction_count = :count,
                       cohort_status = 'COMPLETED',
                       completed_at = CURRENT_TIMESTAMP
                 WHERE id = :id AND cohort_status = 'RUNNING'
                   AND prediction_count = 0
                """).param("count", draft.predictions().size())
                .param("id", cohortId).update();
        if (completed != 1) {
            throw new IllegalStateException(
                    "prediction cohort completion was not atomic");
        }
        return findCompletedByInputHash(draft.inputSha256()).orElseThrow(
                () -> new IllegalStateException(
                        "completed prediction cohort disappeared"));
    }

    private void insertPrediction(
            long cohortId,
            CohortDraft draft,
            PublishedCandidate published,
            int cohortRank) {
        PredictionCandidateSelector.Candidate value = published.candidate();
        jdbc.sql("""
                INSERT INTO prediction
                  (statement, node_id, probability, issued_on, due_on,
                   params_version, cohort_id, node_code, node_name, pillar,
                   node_weight, integration_node, cohort_rank,
                   target_level, horizon_months,
                   raw_probability, calibrated_probability,
                   calibration_version, information_status, input_sha256,
                   statement_sha256, node_set_version, rubric_version,
                   current_level, advance_count, exposure_years,
                   pillar_rate, node_rate, information_score)
                VALUES
                  (:statement, :nodeId, :probability, :issuedOn, :dueOn,
                   :params, :cohortId, :nodeCode, :nodeName, :pillar,
                   :nodeWeight, :integration, :cohortRank,
                   :targetLevel, :horizon,
                   :rawProbability, :calibratedProbability,
                   :calibration, :informationStatus, :inputHash,
                   :statementHash, :nodeSet, :rubric,
                   :currentLevel, :advanceCount, :exposureYears,
                   :pillarRate, :nodeRate, :informationScore)
                """)
                .param("statement", value.statement())
                .param("nodeId", value.nodeId())
                .param("probability", value.issuedProbability())
                .param("issuedOn", Date.valueOf(value.issuedOn()))
                .param("dueOn", Date.valueOf(value.dueOn()))
                .param("params", draft.hazardParamsVersion())
                .param("cohortId", cohortId)
                .param("nodeCode", value.nodeCode())
                .param("nodeName", value.nodeName())
                .param("pillar", value.pillar())
                .param("nodeWeight", value.nodeWeight())
                .param("integration", value.integrationNode() ? "Y" : "N")
                .param("cohortRank", cohortRank)
                .param("targetLevel", value.targetLevel())
                .param("horizon", value.horizonMonths())
                .param("rawProbability", value.rawProbability())
                .param("calibratedProbability", value.calibratedProbability())
                .param("calibration", draft.calibrationVersion())
                .param("informationStatus", value.informationStatus().name())
                .param("inputHash", published.inputSha256())
                .param("statementHash", published.statementSha256())
                .param("nodeSet", draft.nodeSetVersion())
                .param("rubric", draft.rubricVersion())
                .param("currentLevel", value.currentLevel())
                .param("advanceCount", value.advanceCount())
                .param("exposureYears", value.exposureYears())
                .param("pillarRate", value.pillarRate())
                .param("nodeRate", value.nodeRate())
                .param("informationScore", value.informationScore())
                .update();
    }

    private StoredCohort hydrate(CohortRow row) {
        List<PublishedCandidate> predictions = jdbc.sql("""
                SELECT id, node_id, node_code, node_name, pillar,
                       node_weight, integration_node, cohort_rank, current_level,
                       target_level, issued_on, due_on, horizon_months,
                       raw_probability, calibrated_probability, probability,
                       information_score, information_status,
                       calibration_version, statement, advance_count,
                       exposure_years, pillar_rate, node_rate,
                       input_sha256, statement_sha256
                  FROM prediction
                 WHERE cohort_id = :cohortId
                 ORDER BY cohort_rank
                """).param("cohortId", row.id())
                .query(PredictionRepository::mapPublishedCandidate).list();
        if (predictions.size() != row.predictionCount()) {
            throw new IllegalStateException(
                    "prediction cohort count does not match stored rows");
        }
        return new StoredCohort(
                row.id(), row.cohortKey(), row.inputSha256(), row.datasetSha256(),
                row.nodeSetVersion(), row.rubricVersion(),
                row.hazardParamsVersion(), row.calibrationVersion(),
                row.asOf(), row.issuedOn(), predictions,
                row.createdAt(), row.completedAt());
    }

    private static PublishedCandidate mapPublishedCandidate(
            ResultSet rs, int rowNum) throws SQLException {
        PredictionCandidateSelector.Candidate candidate =
                new PredictionCandidateSelector.Candidate(
                        rs.getLong("node_id"), rs.getString("node_code"),
                        rs.getString("node_name"), rs.getInt("pillar"),
                        "Y".equals(rs.getString("integration_node")),
                        rs.getDouble("node_weight"), rs.getInt("current_level"),
                        rs.getInt("target_level"),
                        rs.getDate("issued_on").toLocalDate(),
                        rs.getDate("due_on").toLocalDate(),
                        rs.getInt("horizon_months"),
                        rs.getDouble("raw_probability"),
                        rs.getDouble("calibrated_probability"),
                        rs.getDouble("probability"),
                        rs.getDouble("information_score"),
                        PredictionCandidateSelector.InformationStatus.valueOf(
                                rs.getString("information_status")),
                        rs.getString("calibration_version"),
                        rs.getString("statement"), rs.getInt("advance_count"),
                        rs.getDouble("exposure_years"),
                        rs.getDouble("pillar_rate"), rs.getDouble("node_rate"));
        return new PublishedCandidate(
                candidate, rs.getString("input_sha256").trim(),
                rs.getString("statement_sha256").trim());
    }

    private static CohortRow mapCohort(ResultSet rs, int rowNum)
            throws SQLException {
        return new CohortRow(
                rs.getLong("id"), rs.getString("cohort_key"),
                rs.getString("input_sha256").trim(),
                rs.getString("dataset_sha256").trim(),
                rs.getString("node_set_version"), rs.getString("rubric_version"),
                rs.getString("hazard_params_version"),
                rs.getString("calibration_version"),
                rs.getDate("as_of_date").toLocalDate(),
                rs.getDate("issued_on").toLocalDate(),
                rs.getInt("prediction_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant());
    }

    private static List<Integer> parseHorizons(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .map(Integer::parseInt).toList();
    }

    private static String diagnostics(CohortDraft draft) {
        long informative = draft.predictions().stream().filter(value ->
                value.candidate().informationStatus()
                        == PredictionCandidateSelector.InformationStatus.INFORMATIVE)
                .count();
        return "predictions=" + draft.predictions().size()
                + ";informative=" + informative
                + ";low_information=" + (draft.predictions().size() - informative);
    }

    public record CohortDraft(
            String cohortKey,
            String inputSha256,
            String datasetSha256,
            String nodeSetVersion,
            String rubricVersion,
            String hazardParamsVersion,
            String calibrationVersion,
            LocalDate asOf,
            LocalDate issuedOn,
            List<PublishedCandidate> predictions) {

        public CohortDraft {
            if (cohortKey == null || cohortKey.isBlank() || cohortKey.length() > 80
                    || !sha(inputSha256) || !sha(datasetSha256)
                    || blank(nodeSetVersion) || blank(rubricVersion)
                    || blank(hazardParamsVersion) || blank(calibrationVersion)
                    || asOf == null || issuedOn == null || issuedOn.isBefore(asOf)) {
                throw new IllegalArgumentException("invalid prediction cohort draft");
            }
            predictions = List.copyOf(Objects.requireNonNull(
                    predictions, "predictions"));
            if (predictions.isEmpty() || predictions.size() > 12) {
                throw new IllegalArgumentException("prediction cohort must contain 1-12 rows");
            }
            Set<String> identities = new HashSet<>();
            Map<Integer, Integer> perPillar = new HashMap<>();
            for (PublishedCandidate published : predictions) {
                var candidate = published.candidate();
                if (!calibrationVersion.equals(candidate.calibrationVersion())
                        || !issuedOn.equals(candidate.issuedOn())
                        || !identities.add(candidate.nodeId() + ":"
                                + candidate.targetLevel())) {
                    throw new IllegalArgumentException(
                            "inconsistent or duplicate prediction candidate");
                }
                int count = perPillar.merge(candidate.pillar(), 1, Integer::sum);
                if (count > 2) {
                    throw new IllegalArgumentException(
                            "prediction cohort exceeds the pillar limit");
                }
            }
        }
    }

    public record PublishedCandidate(
            PredictionCandidateSelector.Candidate candidate,
            String inputSha256,
            String statementSha256) {

        public PublishedCandidate {
            candidate = Objects.requireNonNull(candidate, "candidate");
            if (!sha(inputSha256) || !sha(statementSha256)) {
                throw new IllegalArgumentException("invalid published candidate hash");
            }
        }
    }

    public record StoredCohort(
            long id,
            String cohortKey,
            String inputSha256,
            String datasetSha256,
            String nodeSetVersion,
            String rubricVersion,
            String hazardParamsVersion,
            String calibrationVersion,
            LocalDate asOf,
            LocalDate issuedOn,
            List<PublishedCandidate> predictions,
            Instant createdAt,
            Instant completedAt) {

        public StoredCohort {
            predictions = List.copyOf(predictions);
        }

        private boolean draftIdentityEquals(CohortDraft draft) {
            return cohortKey.equals(draft.cohortKey())
                    && inputSha256.equals(draft.inputSha256())
                    && datasetSha256.equals(draft.datasetSha256())
                    && nodeSetVersion.equals(draft.nodeSetVersion())
                    && rubricVersion.equals(draft.rubricVersion())
                    && hazardParamsVersion.equals(draft.hazardParamsVersion())
                    && calibrationVersion.equals(draft.calibrationVersion())
                    && asOf.equals(draft.asOf())
                    && issuedOn.equals(draft.issuedOn())
                    && predictions.equals(draft.predictions());
        }
    }

    private record CohortRow(
            long id,
            String cohortKey,
            String inputSha256,
            String datasetSha256,
            String nodeSetVersion,
            String rubricVersion,
            String hazardParamsVersion,
            String calibrationVersion,
            LocalDate asOf,
            LocalDate issuedOn,
            int predictionCount,
            Instant createdAt,
            Instant completedAt) {
    }

    private static boolean sha(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
