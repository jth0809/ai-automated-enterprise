-- Phase 4 WP4.5: immutable micro-prediction cohorts, resolution evidence,
-- Brier scorecards, probability calibration, and bounded drift diagnostics.
-- Existing prediction rows remain valid; Phase 4 completeness applies only
-- when cohort_id is populated.

CREATE TABLE prediction_parameter_set (
  version_label              VARCHAR2(40) PRIMARY KEY,
  active                     CHAR(1) DEFAULT 'N' NOT NULL,
  node_set_version           VARCHAR2(40) NOT NULL,
  rubric_version             VARCHAR2(40) NOT NULL REFERENCES rubric_version(version_label),
  kappa_node_years           NUMBER(8,3) NOT NULL,
  probability_floor          NUMBER(8,6) NOT NULL,
  probability_ceiling        NUMBER(8,6) NOT NULL,
  horizons_months            VARCHAR2(40) NOT NULL,
  cohort_limit               NUMBER(2) NOT NULL,
  pillar_limit               NUMBER(1) NOT NULL,
  calibration_min_outcomes   NUMBER(4) NOT NULL,
  calibration_min_quarters   NUMBER(2) NOT NULL,
  created_at                 TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_pred_param_active CHECK (active IN ('Y','N')),
  CONSTRAINT ck_pred_param_kappa CHECK (kappa_node_years > 0),
  CONSTRAINT ck_pred_param_bounds CHECK (
    probability_floor >= 0
    AND probability_floor < probability_ceiling
    AND probability_ceiling <= 1),
  CONSTRAINT ck_pred_param_horizons CHECK (
    horizons_months = '6,12,18,24'),
  CONSTRAINT ck_pred_param_limits CHECK (
    cohort_limit BETWEEN 1 AND 12
    AND pillar_limit BETWEEN 1 AND 2
    AND calibration_min_outcomes >= 30
    AND calibration_min_quarters >= 4)
);

INSERT INTO prediction_parameter_set
  (version_label, active, node_set_version, rubric_version,
   kappa_node_years, probability_floor, probability_ceiling,
   horizons_months, cohort_limit, pillar_limit,
   calibration_min_outcomes, calibration_min_quarters)
VALUES
  ('hazard-v1', 'Y', 'nodes-v1.0', 'r2.0',
   4.0, 0.02, 0.98, '6,12,18,24', 12, 2, 30, 4);

CREATE TABLE prediction_calibration_run (
  id                       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  calibration_version      VARCHAR2(80) NOT NULL UNIQUE,
  input_sha256             CHAR(64) NOT NULL UNIQUE,
  method                   VARCHAR2(12) NOT NULL,
  calibration_status       VARCHAR2(40) NOT NULL,
  sample_count             NUMBER(6) DEFAULT 0 NOT NULL,
  quarter_count            NUMBER(4) DEFAULT 0 NOT NULL,
  knots_json               CLOB NOT NULL,
  oos_brier_raw            NUMBER(12,10),
  oos_brier_calibrated     NUMBER(12,10),
  calibration_in_large     NUMBER(12,10),
  diagnostics              VARCHAR2(2000),
  current_result           CHAR(1) DEFAULT 'N' NOT NULL,
  completed_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_pred_cal_sha CHECK (LENGTH(input_sha256) = 64),
  CONSTRAINT ck_pred_cal_method CHECK (method IN ('IDENTITY','PAVA')),
  CONSTRAINT ck_pred_cal_status CHECK (calibration_status IN
    ('OK','INSUFFICIENT_CALIBRATION_DATA')),
  CONSTRAINT ck_pred_cal_counts CHECK (
    sample_count >= 0 AND quarter_count >= 0),
  CONSTRAINT ck_pred_cal_current CHECK (current_result IN ('Y','N')),
  CONSTRAINT ck_pred_cal_metrics CHECK (
    (oos_brier_raw IS NULL OR oos_brier_raw BETWEEN 0 AND 1)
    AND (oos_brier_calibrated IS NULL OR oos_brier_calibrated BETWEEN 0 AND 1)
    AND (calibration_in_large IS NULL
      OR calibration_in_large BETWEEN -1 AND 1)),
  CONSTRAINT ck_pred_cal_gate CHECK (
    (method = 'IDENTITY'
      AND calibration_status = 'INSUFFICIENT_CALIBRATION_DATA')
    OR (method = 'PAVA'
      AND calibration_status = 'OK'
      AND sample_count >= 30
      AND quarter_count >= 4
      AND oos_brier_raw IS NOT NULL
      AND oos_brier_calibrated IS NOT NULL
      AND calibration_in_large IS NOT NULL))
);

CREATE TABLE prediction_cohort (
  id                       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cohort_key               VARCHAR2(80) NOT NULL UNIQUE,
  input_sha256             CHAR(64) NOT NULL UNIQUE,
  dataset_sha256           CHAR(64) NOT NULL,
  node_set_version         VARCHAR2(40) NOT NULL,
  rubric_version           VARCHAR2(40) NOT NULL REFERENCES rubric_version(version_label),
  hazard_params_version    VARCHAR2(40) NOT NULL REFERENCES prediction_parameter_set(version_label),
  calibration_version      VARCHAR2(80) NOT NULL REFERENCES prediction_calibration_run(calibration_version),
  as_of_date               DATE NOT NULL,
  issued_on                DATE NOT NULL,
  prediction_count         NUMBER(2) DEFAULT 0 NOT NULL,
  cohort_status            VARCHAR2(12) NOT NULL,
  diagnostics              VARCHAR2(2000),
  created_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  completed_at             TIMESTAMP,
  CONSTRAINT ck_pred_cohort_input CHECK (LENGTH(input_sha256) = 64),
  CONSTRAINT ck_pred_cohort_data CHECK (LENGTH(dataset_sha256) = 64),
  CONSTRAINT ck_pred_cohort_dates CHECK (issued_on >= as_of_date),
  CONSTRAINT ck_pred_cohort_count CHECK (prediction_count BETWEEN 0 AND 12),
  CONSTRAINT ck_pred_cohort_status CHECK (
    cohort_status IN ('RUNNING','COMPLETED','FAILED')),
  CONSTRAINT ck_pred_cohort_complete CHECK (
    (cohort_status = 'RUNNING'
      AND prediction_count = 0 AND completed_at IS NULL)
    OR (cohort_status = 'COMPLETED'
      AND prediction_count BETWEEN 1 AND 12 AND completed_at IS NOT NULL)
    OR (cohort_status = 'FAILED' AND completed_at IS NOT NULL))
);

ALTER TABLE prediction MODIFY probability NUMBER(8,6);
ALTER TABLE prediction MODIFY brier NUMBER(10,8);

ALTER TABLE prediction ADD (
  cohort_id                 NUMBER REFERENCES prediction_cohort(id),
  node_code                 VARCHAR2(80),
  node_name                 VARCHAR2(200),
  pillar                    NUMBER(1),
  node_weight               NUMBER(8,6),
  integration_node          CHAR(1),
  cohort_rank               NUMBER(2),
  target_level              NUMBER(1),
  horizon_months            NUMBER(2),
  raw_probability           NUMBER(10,8),
  calibrated_probability    NUMBER(10,8),
  calibration_version       VARCHAR2(80),
  information_status        VARCHAR2(24),
  input_sha256              CHAR(64),
  statement_sha256          CHAR(64),
  node_set_version          VARCHAR2(40),
  rubric_version            VARCHAR2(40),
  current_level             NUMBER(1),
  advance_count             NUMBER(4),
  exposure_years            NUMBER(16,10),
  pillar_rate               NUMBER(16,12),
  node_rate                 NUMBER(16,12),
  information_score         NUMBER(12,10),
  resolution_status         VARCHAR2(24) DEFAULT 'PENDING' NOT NULL,
  resolved_at               TIMESTAMP
);

ALTER TABLE prediction ADD CONSTRAINT fk_prediction_cohort
  FOREIGN KEY (cohort_id) REFERENCES prediction_cohort(id);
ALTER TABLE prediction ADD CONSTRAINT fk_prediction_calibration
  FOREIGN KEY (calibration_version)
  REFERENCES prediction_calibration_run(calibration_version);
ALTER TABLE prediction ADD CONSTRAINT fk_prediction_rubric
  FOREIGN KEY (rubric_version) REFERENCES rubric_version(version_label);
ALTER TABLE prediction ADD CONSTRAINT uq_prediction_cohort_target
  UNIQUE (cohort_id, node_id, target_level);
ALTER TABLE prediction ADD CONSTRAINT uq_prediction_cohort_rank
  UNIQUE (cohort_id, cohort_rank);
ALTER TABLE prediction ADD CONSTRAINT ck_prediction_due
  CHECK (due_on > issued_on);
ALTER TABLE prediction ADD CONSTRAINT ck_prediction_brier_range
  CHECK (brier IS NULL OR brier BETWEEN 0 AND 1);
ALTER TABLE prediction ADD CONSTRAINT ck_prediction_resolution_status
  CHECK (resolution_status IN ('PENDING','RESOLVED','VOID','REVIEW_REQUIRED'));
ALTER TABLE prediction ADD CONSTRAINT ck_prediction_phase4_complete
  CHECK (cohort_id IS NULL OR (
    node_id IS NOT NULL
    AND node_code IS NOT NULL
    AND node_name IS NOT NULL
    AND pillar BETWEEN 1 AND 6
    AND node_weight > 0
    AND integration_node IN ('Y','N')
    AND cohort_rank BETWEEN 1 AND 12
    AND target_level BETWEEN 1 AND 8
    AND horizon_months IN (6,12,18,24)
    AND raw_probability BETWEEN 0 AND 1
    AND calibrated_probability BETWEEN 0 AND 1
    AND probability BETWEEN 0.02 AND 0.98
    AND calibration_version IS NOT NULL
    AND information_status IN ('INFORMATIVE','LOW_INFORMATION')
    AND LENGTH(input_sha256) = 64
    AND LENGTH(statement_sha256) = 64
    AND node_set_version IS NOT NULL
    AND rubric_version IS NOT NULL
    AND current_level BETWEEN 0 AND 7
    AND target_level = current_level + 1
    AND advance_count >= 0
    AND exposure_years >= 0
    AND pillar_rate >= 0
    AND node_rate >= 0
    AND information_score BETWEEN 0 AND 0.25));
ALTER TABLE prediction ADD CONSTRAINT ck_prediction_phase4_outcome
  CHECK (cohort_id IS NULL OR (
    (outcome = 'PENDING' AND brier IS NULL AND resolved_at IS NULL
      AND resolution_status IN ('PENDING','REVIEW_REQUIRED'))
    OR (outcome IN ('HIT','MISS') AND brier IS NOT NULL
      AND resolved_at IS NOT NULL AND resolution_status = 'RESOLVED')
    OR (outcome = 'VOID' AND brier IS NULL
      AND resolved_at IS NOT NULL AND resolution_status = 'VOID')));

CREATE INDEX ix_prediction_pending_due
  ON prediction(outcome, due_on, resolution_status);
CREATE INDEX ix_prediction_cohort
  ON prediction(cohort_id, pillar, node_code);

CREATE TABLE prediction_resolution_evidence (
  prediction_id             NUMBER PRIMARY KEY REFERENCES prediction(id),
  outcome                   VARCHAR2(10) NOT NULL,
  outcome_binary            NUMBER(1),
  outcome_event_id          NUMBER REFERENCES event(id),
  evidence_date             DATE NOT NULL,
  resolved_at               TIMESTAMP NOT NULL,
  resolver_version          VARCHAR2(40) NOT NULL,
  reason_code               VARCHAR2(40) NOT NULL,
  evidence_summary          VARCHAR2(1000) NOT NULL,
  created_at                TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_pred_evidence_outcome CHECK (outcome IN ('HIT','MISS','VOID')),
  CONSTRAINT ck_pred_evidence_shape CHECK (
    (outcome = 'HIT' AND outcome_binary = 1
      AND outcome_event_id IS NOT NULL AND reason_code = 'TARGET_REACHED')
    OR (outcome = 'MISS' AND outcome_binary = 0
      AND outcome_event_id IS NULL AND reason_code = 'DUE_NO_TARGET')
    OR (outcome = 'VOID' AND outcome_binary IS NULL
      AND outcome_event_id IS NULL
      AND reason_code = 'PREDICATE_UNADJUDICABLE'))
);

CREATE TABLE prediction_resolution_conflict (
  id                       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  prediction_id            NUMBER NOT NULL REFERENCES prediction(id),
  conflict_sha256          CHAR(64) NOT NULL UNIQUE,
  existing_outcome         VARCHAR2(10) NOT NULL,
  proposed_outcome         VARCHAR2(10) NOT NULL,
  conflict_status          VARCHAR2(12) DEFAULT 'OPEN' NOT NULL,
  details                  VARCHAR2(1000) NOT NULL,
  created_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  resolved_at              TIMESTAMP,
  CONSTRAINT ck_pred_conflict_sha CHECK (LENGTH(conflict_sha256) = 64),
  CONSTRAINT ck_pred_conflict_outcomes CHECK (
    existing_outcome IN ('HIT','MISS','VOID')
    AND proposed_outcome IN ('HIT','MISS','VOID')
    AND existing_outcome <> proposed_outcome),
  CONSTRAINT ck_pred_conflict_status CHECK (
    conflict_status IN ('OPEN','RESOLVED')),
  CONSTRAINT ck_pred_conflict_resolved CHECK (
    (conflict_status = 'OPEN' AND resolved_at IS NULL)
    OR (conflict_status = 'RESOLVED' AND resolved_at IS NOT NULL))
);

CREATE TABLE prediction_drift_alert (
  id                       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  calibration_version      VARCHAR2(80) NOT NULL REFERENCES prediction_calibration_run(calibration_version),
  alert_code               VARCHAR2(40) NOT NULL,
  observed_value           NUMBER(12,10) NOT NULL,
  warning_threshold        NUMBER(12,10) NOT NULL,
  freeze_threshold         NUMBER(12,10),
  severity                 VARCHAR2(12) NOT NULL,
  freeze_issuance          CHAR(1) DEFAULT 'N' NOT NULL,
  alert_status             VARCHAR2(12) DEFAULT 'OPEN' NOT NULL,
  input_sha256             CHAR(64) NOT NULL UNIQUE,
  created_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  resolved_at              TIMESTAMP,
  resolution_note          VARCHAR2(1000),
  CONSTRAINT uq_pred_drift_code UNIQUE (calibration_version, alert_code),
  CONSTRAINT ck_pred_drift_code CHECK (alert_code IN
    ('CALIBRATION_IN_LARGE','BRIER_DETERIORATION','PROBABILITY_CONCENTRATION')),
  CONSTRAINT ck_pred_drift_severity CHECK (severity IN ('WARNING','FREEZE')),
  CONSTRAINT ck_pred_drift_freeze CHECK (freeze_issuance IN ('Y','N')),
  CONSTRAINT ck_pred_drift_status CHECK (alert_status IN ('OPEN','RESOLVED')),
  CONSTRAINT ck_pred_drift_sha CHECK (LENGTH(input_sha256) = 64),
  CONSTRAINT ck_pred_drift_shape CHECK (
    (severity = 'WARNING' AND freeze_issuance = 'N')
    OR (severity = 'FREEZE' AND freeze_issuance = 'Y')),
  CONSTRAINT ck_pred_drift_resolved CHECK (
    (alert_status = 'OPEN' AND resolved_at IS NULL)
    OR (alert_status = 'RESOLVED' AND resolved_at IS NOT NULL
      AND resolution_note IS NOT NULL))
);

COMMENT ON TABLE prediction_parameter_set IS
  'Versioned hazard and calibration gates; independent from ETA structure';
COMMENT ON TABLE prediction_cohort IS
  'Immutable deterministic 6-24 month node-advancement publication cohort';
COMMENT ON TABLE prediction_resolution_evidence IS
  'One auditable confirmed-event, due-date miss, or manual predicate VOID';
COMMENT ON TABLE prediction_calibration_run IS
  'Identity or gated monotone PAVA calibration with out-of-sample diagnostics';
COMMENT ON TABLE prediction_drift_alert IS
  'Prediction-probability drift only; never mutates structural model parameters';
