package com.aienterprise.backend.tracker.prediction;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
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

    private static final String CALIBRATION_SELECT = """
            SELECT id, calibration_version, input_sha256, method,
                   calibration_status, sample_count, quarter_count,
                   knots_json, oos_brier_raw, oos_brier_calibrated,
                   calibration_in_large, diagnostics, current_result,
                   completed_at
              FROM prediction_calibration_run
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

    public List<CalibrationObservation> findCalibrationObservations() {
        return jdbc.sql("""
                SELECT p.id, p.raw_probability, p.probability, p.outcome,
                       p.due_on, p.resolved_at
                  FROM prediction p
                  JOIN prediction_cohort c ON c.id = p.cohort_id
                 WHERE c.cohort_status = 'COMPLETED'
                   AND p.outcome IN ('HIT','MISS')
                   AND p.resolution_status = 'RESOLVED'
                   AND p.raw_probability IS NOT NULL
                   AND p.probability IS NOT NULL
                   AND p.resolved_at IS NOT NULL
                 ORDER BY p.resolved_at, p.id
                """).query((rs, rowNum) -> new CalibrationObservation(
                        rs.getLong("id"), rs.getDouble("raw_probability"),
                        rs.getDouble("probability"),
                        Outcome.valueOf(rs.getString("outcome")),
                        rs.getDate("due_on").toLocalDate(),
                        rs.getTimestamp("resolved_at").toInstant()))
                .list();
    }

    public Optional<StoredCalibration> findCalibrationByInputHash(
            String inputSha256) {
        if (!sha(inputSha256)) {
            throw new IllegalArgumentException("invalid calibration input hash");
        }
        return jdbc.sql(CALIBRATION_SELECT + """
                 WHERE input_sha256 = :inputHash
                """).param("inputHash", inputSha256)
                .query(PredictionRepository::mapCalibration).optional();
    }

    public Optional<StoredCalibration> findCurrentCalibration() {
        return jdbc.sql(CALIBRATION_SELECT + """
                 WHERE current_result = 'Y'
                 ORDER BY completed_at DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """).query(PredictionRepository::mapCalibration).optional();
    }

    @Transactional
    public CalibrationSaveResult saveCalibration(CalibrationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        Optional<StoredCalibration> existing = findCalibrationByInputHash(
                draft.inputSha256());
        if (existing.isPresent()) {
            if (!existing.get().sameIdentity(draft)) {
                throw new IllegalStateException(
                        "calibration input hash belongs to another result");
            }
            activateCalibration(existing.get().id());
            return new CalibrationSaveResult(
                    findCalibrationByInputHash(draft.inputSha256()).orElseThrow(),
                    true);
        }
        int versionCollision = jdbc.sql("""
                SELECT COUNT(*) FROM prediction_calibration_run
                 WHERE calibration_version = :version
                """).param("version", draft.calibrationVersion())
                .query(Integer.class).single();
        if (versionCollision != 0) {
            throw new IllegalStateException("calibration version collision");
        }
        jdbc.sql("""
                UPDATE prediction_calibration_run SET current_result = 'N'
                 WHERE current_result = 'Y'
                """).update();
        jdbc.sql("""
                INSERT INTO prediction_calibration_run
                  (calibration_version, input_sha256, method,
                   calibration_status, sample_count, quarter_count,
                   knots_json, oos_brier_raw, oos_brier_calibrated,
                   calibration_in_large, diagnostics, current_result)
                VALUES
                  (:version, :inputHash, :method,
                   :status, :samples, :quarters,
                   :knots, :rawBrier, :calibratedBrier,
                   :calibrationInLarge, :diagnostics, 'Y')
                """)
                .param("version", draft.calibrationVersion())
                .param("inputHash", draft.inputSha256())
                .param("method", draft.method().name())
                .param("status", draft.status().name())
                .param("samples", draft.sampleCount())
                .param("quarters", draft.quarterCount())
                .param("knots", draft.knotsJson())
                .param("rawBrier", draft.oosBrierRaw(), Types.NUMERIC)
                .param("calibratedBrier", draft.oosBrierCalibrated(),
                        Types.NUMERIC)
                .param("calibrationInLarge", draft.calibrationInLarge(),
                        Types.NUMERIC)
                .param("diagnostics", draft.diagnostics())
                .update();
        StoredCalibration saved = findCalibrationByInputHash(draft.inputSha256())
                .orElseThrow(() -> new IllegalStateException(
                        "saved calibration disappeared"));
        return new CalibrationSaveResult(saved, false);
    }

    @Transactional
    public List<StoredDriftAlert> saveDriftAlerts(
            String calibrationVersion,
            List<DriftAlertDraft> drafts) {
        if (blank(calibrationVersion)) {
            throw new IllegalArgumentException("calibration version is required");
        }
        List<DriftAlertDraft> values = List.copyOf(
                Objects.requireNonNull(drafts, "drafts"));
        List<StoredDriftAlert> stored = new ArrayList<>();
        for (DriftAlertDraft draft : values) {
            Optional<StoredDriftAlert> existing = findDriftAlertByInputHash(
                    draft.inputSha256());
            if (existing.isPresent()) {
                if (!existing.get().sameIdentity(calibrationVersion, draft)) {
                    throw new IllegalStateException(
                            "drift input hash belongs to another alert");
                }
                stored.add(existing.get());
                continue;
            }
            int codeCollision = jdbc.sql("""
                    SELECT COUNT(*) FROM prediction_drift_alert
                     WHERE calibration_version = :version
                       AND alert_code = :code
                    """).param("version", calibrationVersion)
                    .param("code", draft.code().name())
                    .query(Integer.class).single();
            if (codeCollision != 0) {
                throw new IllegalStateException(
                        "drift alert code collision for immutable calibration");
            }
            jdbc.sql("""
                    INSERT INTO prediction_drift_alert
                      (calibration_version, alert_code, observed_value,
                       warning_threshold, freeze_threshold, severity,
                       freeze_issuance, alert_status, input_sha256)
                    VALUES
                      (:version, :code, :observed,
                       :warning, :freezeThreshold, :severity,
                       :freeze, 'OPEN', :inputHash)
                    """).param("version", calibrationVersion)
                    .param("code", draft.code().name())
                    .param("observed", draft.observedValue())
                    .param("warning", draft.warningThreshold())
                    .param("freezeThreshold", draft.freezeThreshold(),
                            Types.NUMERIC)
                    .param("severity", draft.severity().name())
                    .param("freeze", draft.freezeIssuance() ? "Y" : "N")
                    .param("inputHash", draft.inputSha256()).update();
            stored.add(findDriftAlertByInputHash(draft.inputSha256())
                    .orElseThrow(() -> new IllegalStateException(
                            "saved drift alert disappeared")));
        }
        return List.copyOf(stored);
    }

    public boolean isIssuanceFrozen(String calibrationVersion) {
        if (blank(calibrationVersion)) {
            throw new IllegalArgumentException("calibration version is required");
        }
        return jdbc.sql("""
                SELECT COUNT(*) FROM prediction_drift_alert
                 WHERE calibration_version = :version
                   AND alert_status = 'OPEN'
                   AND freeze_issuance = 'Y'
                """).param("version", calibrationVersion)
                .query(Integer.class).single() > 0;
    }

    public List<StoredDriftAlert> findOpenDriftAlerts(
            String calibrationVersion) {
        if (blank(calibrationVersion)) {
            throw new IllegalArgumentException("calibration version is required");
        }
        return jdbc.sql("""
                SELECT id, calibration_version, alert_code, observed_value,
                       warning_threshold, freeze_threshold, severity,
                       freeze_issuance, input_sha256, created_at
                  FROM prediction_drift_alert
                 WHERE calibration_version = :version
                   AND alert_status = 'OPEN'
                 ORDER BY id
                """).param("version", calibrationVersion)
                .query(PredictionRepository::mapDriftAlert).list();
    }

    public Optional<StoredCohort> findCompletedByInputHash(String inputSha256) {
        return jdbc.sql(COHORT_SELECT + """
                 WHERE input_sha256 = :inputHash
                   AND cohort_status = 'COMPLETED'
                """).param("inputHash", inputSha256)
                .query(PredictionRepository::mapCohort)
                .optional().map(this::hydrate);
    }

    public List<PendingPrediction> findPendingDue(LocalDate through) {
        Objects.requireNonNull(through, "through");
        return jdbc.sql("""
                SELECT p.id, p.node_id, p.node_code, p.integration_node,
                       p.target_level, p.probability, p.issued_on, p.due_on,
                       p.node_set_version, p.rubric_version
                  FROM prediction p
                  JOIN prediction_cohort c ON c.id = p.cohort_id
                 WHERE c.cohort_status = 'COMPLETED'
                   AND p.outcome = 'PENDING'
                   AND p.resolution_status = 'PENDING'
                   AND p.due_on <= :through
                 ORDER BY p.due_on, p.id
                """).param("through", Date.valueOf(through))
                .query(PredictionRepository::mapPendingPrediction).list();
    }

    public Optional<PendingPrediction> findForResolution(long predictionId) {
        return jdbc.sql("""
                SELECT p.id, p.node_id, p.node_code, p.integration_node,
                       p.target_level, p.probability, p.issued_on, p.due_on,
                       p.node_set_version, p.rubric_version
                  FROM prediction p
                  JOIN prediction_cohort c ON c.id = p.cohort_id
                 WHERE p.id = :predictionId
                   AND c.cohort_status = 'COMPLETED'
                   AND p.outcome = 'PENDING'
                """).param("predictionId", predictionId)
                .query(PredictionRepository::mapPendingPrediction).optional();
    }

    public Optional<TargetTransition> findFirstTargetTransition(
            PendingPrediction prediction) {
        Objects.requireNonNull(prediction, "prediction");
        return jdbc.sql("""
                SELECT e.id, e.occurred_on
                  FROM node_state_history h
                  JOIN event e ON e.id = h.cause_event_id
                 WHERE h.node_id = :nodeId
                   AND e.node_id = :nodeId
                   AND e.event_status = 'CONFIRMED'
                   AND e.occurred_on > :issuedOn
                   AND e.occurred_on <= :dueOn
                   AND h.prev_level < :targetLevel
                   AND h.new_level >= :targetLevel
                 ORDER BY e.occurred_on, e.id, h.id
                 FETCH FIRST 1 ROWS ONLY
                """)
                .param("nodeId", prediction.nodeId())
                .param("issuedOn", Date.valueOf(prediction.issuedOn()))
                .param("dueOn", Date.valueOf(prediction.dueOn()))
                .param("targetLevel", prediction.targetLevel())
                .query((rs, rowNum) -> new TargetTransition(
                        rs.getLong("id"),
                        rs.getDate("occurred_on").toLocalDate()))
                .optional();
    }

    @Transactional
    public ResolutionResult resolve(ResolutionDraft draft) {
        Objects.requireNonNull(draft, "draft");
        ResolutionTarget target = jdbc.sql("""
                SELECT p.id, p.node_id, p.node_code, p.integration_node,
                       p.target_level, p.probability, p.issued_on, p.due_on,
                       p.node_set_version, p.rubric_version,
                       p.outcome, p.brier
                  FROM prediction p
                  JOIN prediction_cohort c ON c.id = p.cohort_id
                 WHERE p.id = :predictionId
                   AND c.cohort_status = 'COMPLETED'
                """).param("predictionId", draft.predictionId())
                .query(PredictionRepository::mapResolutionTarget)
                .optional().orElseThrow(() -> new IllegalArgumentException(
                        "unknown completed prediction"));
        if (target.outcome() != Outcome.PENDING) {
            if (target.outcome() == draft.outcome()) {
                return new ResolutionResult(
                        ResolutionStatus.REUSED, target.id(), target.outcome(),
                        target.brier(), null);
            }
            long conflictId = saveConflict(target, draft);
            return new ResolutionResult(
                    ResolutionStatus.CONFLICT, target.id(), target.outcome(),
                    target.brier(), conflictId);
        }

        PendingPrediction pending = target.pending();
        validateResolutionEvidence(pending, draft);
        Double brier = switch (draft.outcome()) {
            case HIT -> square(target.probability() - 1);
            case MISS -> square(target.probability());
            case VOID -> null;
            case PENDING -> throw new IllegalArgumentException(
                    "PENDING is not a resolution outcome");
        };
        String resolutionStatus = draft.outcome() == Outcome.VOID
                ? "VOID" : "RESOLVED";
        int updated = jdbc.sql("""
                UPDATE prediction
                   SET outcome = :outcome,
                       brier = :brier,
                       resolution_status = :resolutionStatus,
                       resolved_at = :resolvedAt
                 WHERE id = :predictionId AND outcome = 'PENDING'
                """)
                .param("outcome", draft.outcome().name())
                .param("brier", brier, Types.NUMERIC)
                .param("resolutionStatus", resolutionStatus)
                .param("resolvedAt", Timestamp.from(draft.resolvedAt()))
                .param("predictionId", draft.predictionId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "prediction resolution lost its atomic guard");
        }
        jdbc.sql("""
                INSERT INTO prediction_resolution_evidence
                  (prediction_id, outcome, outcome_binary, outcome_event_id,
                   evidence_date, resolved_at, resolver_version, reason_code,
                   evidence_summary)
                VALUES
                  (:predictionId, :outcome, :outcomeBinary, :eventId,
                   :evidenceDate, :resolvedAt, 'resolver-v1', :reasonCode,
                   :summary)
                """)
                .param("predictionId", draft.predictionId())
                .param("outcome", draft.outcome().name())
                .param("outcomeBinary", draft.outcomeBinary(), Types.INTEGER)
                .param("eventId", draft.outcomeEventId(), Types.BIGINT)
                .param("evidenceDate", Date.valueOf(draft.evidenceDate()))
                .param("resolvedAt", Timestamp.from(draft.resolvedAt()))
                .param("reasonCode", draft.reasonCode())
                .param("summary", draft.evidenceSummary())
                .update();
        return new ResolutionResult(
                ResolutionStatus.APPLIED, target.id(), draft.outcome(),
                brier, null);
    }

    public List<ScoredPrediction> findScoredPredictions() {
        return jdbc.sql("""
                SELECT p.id, c.cohort_key, p.pillar, p.horizon_months,
                       p.outcome, p.probability, p.brier, p.due_on
                  FROM prediction p
                  JOIN prediction_cohort c ON c.id = p.cohort_id
                 WHERE c.cohort_status = 'COMPLETED'
                   AND p.outcome IN ('HIT','MISS')
                   AND p.resolution_status = 'RESOLVED'
                   AND p.brier IS NOT NULL
                 ORDER BY p.resolved_at, p.id
                """).query((rs, rowNum) -> new ScoredPrediction(
                        rs.getLong("id"), rs.getString("cohort_key"),
                        rs.getInt("pillar"), rs.getInt("horizon_months"),
                        Outcome.valueOf(rs.getString("outcome")),
                        rs.getDouble("probability"), rs.getDouble("brier"),
                        rs.getDate("due_on").toLocalDate()))
                .list();
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

    private void validateResolutionEvidence(
            PendingPrediction prediction,
            ResolutionDraft draft) {
        if (draft.outcome() == Outcome.HIT || draft.outcome() == Outcome.MISS) {
            if (!prediction.dueOn().equals(draft.evidenceDate())) {
                throw new IllegalArgumentException(
                        "mature prediction evidence date must equal its due date");
            }
            Optional<TargetTransition> transition =
                    findFirstTargetTransition(prediction);
            if (draft.outcome() == Outcome.HIT) {
                if (transition.isEmpty()
                        || !Objects.equals(draft.outcomeEventId(),
                                transition.get().eventId())) {
                    throw new IllegalStateException(
                            "HIT requires the first confirmed target transition");
                }
            } else if (transition.isPresent()) {
                throw new IllegalStateException(
                        "confirmed target transition prevents MISS resolution");
            }
        }
    }

    private long saveConflict(
            ResolutionTarget target,
            ResolutionDraft proposed) {
        String conflictHash = PredictionFingerprint.sha256(String.join("|",
                "prediction-resolution-conflict-v1",
                Long.toString(target.id()), target.outcome().name(),
                proposed.outcome().name(),
                String.valueOf(proposed.outcomeEventId()),
                proposed.evidenceDate().toString(), proposed.reasonCode()));
        Optional<Long> existing = jdbc.sql("""
                SELECT id FROM prediction_resolution_conflict
                 WHERE conflict_sha256 = :conflictHash
                """).param("conflictHash", conflictHash)
                .query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.sql("""
                INSERT INTO prediction_resolution_conflict
                  (prediction_id, conflict_sha256, existing_outcome,
                   proposed_outcome, conflict_status, details)
                VALUES
                  (:predictionId, :conflictHash, :existing,
                   :proposed, 'OPEN', :details)
                """)
                .param("predictionId", target.id())
                .param("conflictHash", conflictHash)
                .param("existing", target.outcome().name())
                .param("proposed", proposed.outcome().name())
                .param("details", "Immutable outcome conflict: existing="
                        + target.outcome() + ";proposed=" + proposed.outcome())
                .update();
        return jdbc.sql("""
                SELECT id FROM prediction_resolution_conflict
                 WHERE conflict_sha256 = :conflictHash
                """).param("conflictHash", conflictHash)
                .query(Long.class).single();
    }

    private void activateCalibration(long calibrationId) {
        jdbc.sql("""
                UPDATE prediction_calibration_run SET current_result = 'N'
                 WHERE current_result = 'Y' AND id <> :id
                """).param("id", calibrationId).update();
        int activated = jdbc.sql("""
                UPDATE prediction_calibration_run SET current_result = 'Y'
                 WHERE id = :id
                """).param("id", calibrationId).update();
        if (activated != 1) {
            throw new IllegalStateException("calibration activation failed");
        }
    }

    private Optional<StoredDriftAlert> findDriftAlertByInputHash(
            String inputSha256) {
        if (!sha(inputSha256)) {
            throw new IllegalArgumentException("invalid drift input hash");
        }
        return jdbc.sql("""
                SELECT id, calibration_version, alert_code, observed_value,
                       warning_threshold, freeze_threshold, severity,
                       freeze_issuance, input_sha256, created_at
                  FROM prediction_drift_alert
                 WHERE input_sha256 = :inputHash
                """).param("inputHash", inputSha256)
                .query(PredictionRepository::mapDriftAlert).optional();
    }

    private static StoredCalibration mapCalibration(
            ResultSet rs, int rowNum) throws SQLException {
        Number rawBrier = (Number) rs.getObject("oos_brier_raw");
        Number calibratedBrier = (Number) rs.getObject(
                "oos_brier_calibrated");
        Number calibrationInLarge = (Number) rs.getObject(
                "calibration_in_large");
        return new StoredCalibration(
                rs.getLong("id"), rs.getString("calibration_version"),
                rs.getString("input_sha256"),
                CalibrationMethod.valueOf(rs.getString("method")),
                CalibrationStatus.valueOf(rs.getString("calibration_status")),
                rs.getInt("sample_count"), rs.getInt("quarter_count"),
                rs.getString("knots_json"),
                rawBrier == null ? null : rawBrier.doubleValue(),
                calibratedBrier == null ? null
                        : calibratedBrier.doubleValue(),
                calibrationInLarge == null ? null
                        : calibrationInLarge.doubleValue(),
                rs.getString("diagnostics"),
                "Y".equals(rs.getString("current_result")),
                rs.getTimestamp("completed_at").toInstant());
    }

    private static StoredDriftAlert mapDriftAlert(
            ResultSet rs, int rowNum) throws SQLException {
        Number freezeThreshold = (Number) rs.getObject("freeze_threshold");
        return new StoredDriftAlert(
                rs.getLong("id"), rs.getString("calibration_version"),
                PredictionDriftDetector.AlertCode.valueOf(
                        rs.getString("alert_code")),
                rs.getDouble("observed_value"),
                rs.getDouble("warning_threshold"),
                freezeThreshold == null ? null
                        : freezeThreshold.doubleValue(),
                PredictionDriftDetector.Severity.valueOf(
                        rs.getString("severity")),
                "Y".equals(rs.getString("freeze_issuance")),
                rs.getString("input_sha256"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static PendingPrediction mapPendingPrediction(
            ResultSet rs, int rowNum) throws SQLException {
        return new PendingPrediction(
                rs.getLong("id"), rs.getLong("node_id"),
                rs.getString("node_code"),
                "Y".equals(rs.getString("integration_node")),
                rs.getInt("target_level"), rs.getDouble("probability"),
                rs.getDate("issued_on").toLocalDate(),
                rs.getDate("due_on").toLocalDate(),
                rs.getString("node_set_version"),
                rs.getString("rubric_version"));
    }

    private static ResolutionTarget mapResolutionTarget(
            ResultSet rs, int rowNum) throws SQLException {
        Number brier = (Number) rs.getObject("brier");
        PendingPrediction pending = mapPendingPrediction(rs, rowNum);
        return new ResolutionTarget(
                pending, Outcome.valueOf(rs.getString("outcome")),
                brier == null ? null : brier.doubleValue());
    }

    private static double square(double value) {
        return value * value;
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

    public enum CalibrationMethod {
        IDENTITY,
        PAVA
    }

    public enum CalibrationStatus {
        OK,
        INSUFFICIENT_CALIBRATION_DATA
    }

    public record CalibrationObservation(
            long id,
            double rawProbability,
            double issuedProbability,
            Outcome outcome,
            LocalDate dueOn,
            Instant resolvedAt) {

        public CalibrationObservation {
            if (id <= 0 || !unit(rawProbability)
                    || !Double.isFinite(issuedProbability)
                    || issuedProbability < 0.02 || issuedProbability > 0.98
                    || outcome != Outcome.HIT && outcome != Outcome.MISS
                    || dueOn == null || resolvedAt == null
                    || resolvedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            .isBefore(dueOn)) {
                throw new IllegalArgumentException(
                        "invalid calibration observation");
            }
        }

        public int outcomeBinary() {
            return outcome == Outcome.HIT ? 1 : 0;
        }
    }

    public record CalibrationDraft(
            String calibrationVersion,
            String inputSha256,
            CalibrationMethod method,
            CalibrationStatus status,
            int sampleCount,
            int quarterCount,
            String knotsJson,
            Double oosBrierRaw,
            Double oosBrierCalibrated,
            Double calibrationInLarge,
            String diagnostics) {

        public CalibrationDraft {
            boolean identity = method == CalibrationMethod.IDENTITY
                    && status == CalibrationStatus
                            .INSUFFICIENT_CALIBRATION_DATA
                    && "[]".equals(knotsJson)
                    && oosBrierRaw == null && oosBrierCalibrated == null
                    && calibrationInLarge == null;
            boolean pava = method == CalibrationMethod.PAVA
                    && status == CalibrationStatus.OK
                    && sampleCount >= 30 && quarterCount >= 4
                    && knotsJson != null && !"[]".equals(knotsJson)
                    && metric(oosBrierRaw) && metric(oosBrierCalibrated)
                    && signedMetric(calibrationInLarge);
            if (blank(calibrationVersion) || calibrationVersion.length() > 80
                    || !sha(inputSha256) || method == null || status == null
                    || sampleCount < 0 || quarterCount < 0
                    || blank(knotsJson) || blank(diagnostics)
                    || diagnostics.length() > 2000 || !identity && !pava) {
                throw new IllegalArgumentException("invalid calibration draft");
            }
        }
    }

    public record StoredCalibration(
            long id,
            String calibrationVersion,
            String inputSha256,
            CalibrationMethod method,
            CalibrationStatus status,
            int sampleCount,
            int quarterCount,
            String knotsJson,
            Double oosBrierRaw,
            Double oosBrierCalibrated,
            Double calibrationInLarge,
            String diagnostics,
            boolean current,
            Instant completedAt) {

        public StoredCalibration {
            if (id <= 0 || completedAt == null) {
                throw new IllegalArgumentException("invalid stored calibration");
            }
            new CalibrationDraft(
                    calibrationVersion, inputSha256, method, status,
                    sampleCount, quarterCount, knotsJson, oosBrierRaw,
                    oosBrierCalibrated, calibrationInLarge, diagnostics);
        }

        private boolean sameIdentity(CalibrationDraft draft) {
            return calibrationVersion.equals(draft.calibrationVersion())
                    && inputSha256.equals(draft.inputSha256())
                    && method == draft.method() && status == draft.status()
                    && sampleCount == draft.sampleCount()
                    && quarterCount == draft.quarterCount()
                    && knotsJson.equals(draft.knotsJson())
                    && diagnostics.equals(draft.diagnostics())
                    && close(oosBrierRaw, draft.oosBrierRaw())
                    && close(oosBrierCalibrated,
                            draft.oosBrierCalibrated())
                    && close(calibrationInLarge,
                            draft.calibrationInLarge());
        }
    }

    public record CalibrationSaveResult(
            StoredCalibration calibration,
            boolean reused) {

        public CalibrationSaveResult {
            calibration = Objects.requireNonNull(calibration, "calibration");
        }
    }

    public record DriftAlertDraft(
            PredictionDriftDetector.AlertCode code,
            double observedValue,
            double warningThreshold,
            Double freezeThreshold,
            PredictionDriftDetector.Severity severity,
            boolean freezeIssuance,
            String inputSha256) {

        public DriftAlertDraft {
            boolean concentration = code == PredictionDriftDetector.AlertCode
                    .PROBABILITY_CONCENTRATION;
            if (code == null || !Double.isFinite(observedValue)
                    || code != PredictionDriftDetector.AlertCode
                            .CALIBRATION_IN_LARGE && observedValue < 0
                    || !Double.isFinite(warningThreshold)
                    || warningThreshold <= 0
                    || concentration != (freezeThreshold == null)
                    || freezeThreshold != null
                            && (!Double.isFinite(freezeThreshold)
                                    || freezeThreshold <= warningThreshold)
                    || severity == null
                    || freezeIssuance != (severity
                            == PredictionDriftDetector.Severity.FREEZE)
                    || concentration && freezeIssuance
                    || !sha(inputSha256)) {
                throw new IllegalArgumentException("invalid drift alert draft");
            }
        }
    }

    public record StoredDriftAlert(
            long id,
            String calibrationVersion,
            PredictionDriftDetector.AlertCode code,
            double observedValue,
            double warningThreshold,
            Double freezeThreshold,
            PredictionDriftDetector.Severity severity,
            boolean freezeIssuance,
            String inputSha256,
            Instant createdAt) {

        public StoredDriftAlert {
            if (id <= 0 || blank(calibrationVersion) || createdAt == null) {
                throw new IllegalArgumentException("invalid stored drift alert");
            }
            new DriftAlertDraft(code, observedValue, warningThreshold,
                    freezeThreshold, severity, freezeIssuance, inputSha256);
        }

        private boolean sameIdentity(
                String expectedCalibration,
                DriftAlertDraft draft) {
            return calibrationVersion.equals(expectedCalibration)
                    && code == draft.code()
                    && close(observedValue, draft.observedValue())
                    && close(warningThreshold, draft.warningThreshold())
                    && close(freezeThreshold, draft.freezeThreshold())
                    && severity == draft.severity()
                    && freezeIssuance == draft.freezeIssuance()
                    && inputSha256.equals(draft.inputSha256());
        }
    }

    public enum Outcome {
        PENDING,
        HIT,
        MISS,
        VOID
    }

    public enum ResolutionStatus {
        APPLIED,
        REUSED,
        CONFLICT
    }

    public record PendingPrediction(
            long id,
            long nodeId,
            String nodeCode,
            boolean integrationNode,
            int targetLevel,
            double issuedProbability,
            LocalDate issuedOn,
            LocalDate dueOn,
            String nodeSetVersion,
            String rubricVersion) {

        public PendingPrediction {
            if (id <= 0 || nodeId <= 0 || blank(nodeCode)
                    || targetLevel < 1 || targetLevel > 8
                    || !Double.isFinite(issuedProbability)
                    || issuedProbability < 0.02 || issuedProbability > 0.98
                    || issuedOn == null || dueOn == null || !dueOn.isAfter(issuedOn)
                    || blank(nodeSetVersion) || blank(rubricVersion)) {
                throw new IllegalArgumentException("invalid pending prediction");
            }
        }
    }

    public record TargetTransition(long eventId, LocalDate occurredOn) {

        public TargetTransition {
            if (eventId <= 0 || occurredOn == null) {
                throw new IllegalArgumentException("invalid target transition");
            }
        }
    }

    public record ResolutionDraft(
            long predictionId,
            Outcome outcome,
            Long outcomeEventId,
            LocalDate evidenceDate,
            Instant resolvedAt,
            String reasonCode,
            String evidenceSummary) {

        public ResolutionDraft {
            if (predictionId <= 0 || outcome == null || outcome == Outcome.PENDING
                    || evidenceDate == null || resolvedAt == null
                    || blank(reasonCode) || blank(evidenceSummary)
                    || evidenceSummary.length() > 1000) {
                throw new IllegalArgumentException("invalid prediction resolution draft");
            }
            boolean invalid = switch (outcome) {
                case HIT -> outcomeEventId == null || outcomeEventId <= 0
                        || !"TARGET_REACHED".equals(reasonCode);
                case MISS -> outcomeEventId != null
                        || !"DUE_NO_TARGET".equals(reasonCode);
                case VOID -> outcomeEventId != null
                        || !"PREDICATE_UNADJUDICABLE".equals(reasonCode);
                case PENDING -> true;
            };
            if (invalid) {
                throw new IllegalArgumentException(
                        "resolution evidence does not match its outcome");
            }
            evidenceSummary = evidenceSummary.trim();
        }

        public static ResolutionDraft hit(
                long predictionId,
                long eventId,
                LocalDate dueOn,
                Instant resolvedAt) {
            return new ResolutionDraft(
                    predictionId, Outcome.HIT, eventId, dueOn, resolvedAt,
                    "TARGET_REACHED",
                    "The first confirmed target transition occurred by the immutable due date.");
        }

        public static ResolutionDraft miss(
                long predictionId,
                LocalDate dueOn,
                Instant resolvedAt) {
            return new ResolutionDraft(
                    predictionId, Outcome.MISS, null, dueOn, resolvedAt,
                    "DUE_NO_TARGET",
                    "No confirmed target transition occurred by the immutable due date.");
        }

        public static ResolutionDraft voided(
                long predictionId,
                LocalDate evidenceDate,
                Instant resolvedAt,
                String evidenceSummary) {
            return new ResolutionDraft(
                    predictionId, Outcome.VOID, null, evidenceDate, resolvedAt,
                    "PREDICATE_UNADJUDICABLE", evidenceSummary);
        }

        Integer outcomeBinary() {
            return switch (outcome) {
                case HIT -> 1;
                case MISS -> 0;
                case VOID, PENDING -> null;
            };
        }
    }

    public record ResolutionResult(
            ResolutionStatus status,
            long predictionId,
            Outcome outcome,
            Double brier,
            Long conflictId) {

        public ResolutionResult {
            if (status == null || predictionId <= 0 || outcome == null
                    || brier != null && (!Double.isFinite(brier)
                            || brier < 0 || brier > 1)
                    || status == ResolutionStatus.CONFLICT && conflictId == null
                    || status != ResolutionStatus.CONFLICT && conflictId != null) {
                throw new IllegalArgumentException("invalid resolution result");
            }
        }
    }

    public record ScoredPrediction(
            long id,
            String cohortKey,
            int pillar,
            int horizonMonths,
            Outcome outcome,
            double issuedProbability,
            double brier,
            LocalDate dueOn) {

        public ScoredPrediction {
            if (id <= 0 || blank(cohortKey) || pillar < 1 || pillar > 6
                    || !List.of(6, 12, 18, 24).contains(horizonMonths)
                    || outcome != Outcome.HIT && outcome != Outcome.MISS
                    || !Double.isFinite(issuedProbability)
                    || issuedProbability < 0 || issuedProbability > 1
                    || !Double.isFinite(brier) || brier < 0 || brier > 1
                    || dueOn == null) {
                throw new IllegalArgumentException("invalid scored prediction");
            }
        }
    }

    private record ResolutionTarget(
            PendingPrediction pending,
            Outcome outcome,
            Double brier) {

        private long id() {
            return pending.id();
        }

        private double probability() {
            return pending.issuedProbability();
        }
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

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static boolean metric(Double value) {
        return value != null && unit(value);
    }

    private static boolean signedMetric(Double value) {
        return value != null && Double.isFinite(value)
                && value >= -1 && value <= 1;
    }

    private static boolean close(Double left, Double right) {
        if (left == null || right == null) {
            return left == right;
        }
        return Math.abs(left - right) <= 1e-9;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
