-- Phase 4 WP4.4: immutable cutoff-hindcast, calibration, and holdout audit.
-- Existing model parameters, projections, snapshots, and claims are preserved.

CREATE TABLE backtest_run (
  id                         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  input_sha256               CHAR(64) NOT NULL UNIQUE,
  dataset_sha256             CHAR(64) NOT NULL,
  node_set_version           VARCHAR2(40) NOT NULL,
  rubric_version             VARCHAR2(40) NOT NULL REFERENCES rubric_version(version_label),
  params_version             VARCHAR2(40) NOT NULL REFERENCES parameter_set(version_label),
  graph_version              VARCHAR2(40) NOT NULL REFERENCES capability_graph_version(version_label),
  candidate_registry_version VARCHAR2(40) NOT NULL,
  calibration_start          DATE NOT NULL,
  calibration_end            DATE NOT NULL,
  holdout_start              DATE NOT NULL,
  holdout_end                DATE NOT NULL,
  horizon_weeks              NUMBER(3) NOT NULL,
  sample_count               NUMBER(5) NOT NULL,
  selected_window_m          NUMBER(2) NOT NULL,
  selected_k_shrink          NUMBER(8,3) NOT NULL,
  selected_delta_scale       NUMBER(4,2) NOT NULL,
  objective_score            NUMBER(16,12) NOT NULL,
  run_status                 VARCHAR2(12) NOT NULL,
  diagnostics                VARCHAR2(2000),
  report_json                CLOB,
  report_sha256              CHAR(64),
  current_result             CHAR(1) DEFAULT 'N' NOT NULL,
  started_at                 TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  completed_at               TIMESTAMP,
  CONSTRAINT ck_backtest_input_sha CHECK (LENGTH(input_sha256) = 64),
  CONSTRAINT ck_backtest_dataset_sha CHECK (LENGTH(dataset_sha256) = 64),
  CONSTRAINT ck_backtest_report_sha CHECK (
    report_sha256 IS NULL OR LENGTH(report_sha256) = 64),
  CONSTRAINT ck_backtest_split CHECK (
    calibration_start <= calibration_end
    AND calibration_end < holdout_start
    AND holdout_start <= holdout_end),
  CONSTRAINT ck_backtest_horizon CHECK (horizon_weeks BETWEEN 1 AND 156),
  CONSTRAINT ck_backtest_samples CHECK (sample_count BETWEEN 1000 AND 10000),
  CONSTRAINT ck_backtest_window_m CHECK (selected_window_m IN (4,6,8)),
  CONSTRAINT ck_backtest_k CHECK (selected_k_shrink IN (2,4,8)),
  CONSTRAINT ck_backtest_delta CHECK (selected_delta_scale IN (0.75,1.00,1.25)),
  CONSTRAINT ck_backtest_objective CHECK (
    objective_score BETWEEN 0 AND 1),
  CONSTRAINT ck_backtest_status CHECK (
    run_status IN ('RUNNING','COMPLETED','FAILED')),
  CONSTRAINT ck_backtest_current CHECK (current_result IN ('Y','N')),
  CONSTRAINT ck_backtest_current_complete CHECK (
    current_result = 'N' OR run_status = 'COMPLETED'),
  CONSTRAINT ck_backtest_completion CHECK (
    (run_status = 'RUNNING'
      AND completed_at IS NULL
      AND report_json IS NULL
      AND report_sha256 IS NULL)
    OR (run_status = 'COMPLETED'
      AND completed_at IS NOT NULL
      AND report_json IS NOT NULL
      AND report_sha256 IS NOT NULL)
    OR (run_status = 'FAILED'
      AND completed_at IS NOT NULL
      AND current_result = 'N'))
);

CREATE TABLE backtest_fold (
  run_id                NUMBER NOT NULL REFERENCES backtest_run(id),
  fold_index            NUMBER(4) NOT NULL,
  cohort                VARCHAR2(12) NOT NULL,
  cutoff_date           DATE NOT NULL,
  target_date           DATE NOT NULL,
  pillar                NUMBER(1) NOT NULL,
  current_readiness     NUMBER(12,10),
  predicted_readiness   NUMBER(12,10),
  actual_readiness      NUMBER(12,10),
  predicted_logit       NUMBER(16,10),
  actual_logit          NUMBER(16,10),
  predicted_advance     CHAR(1),
  actual_advance        CHAR(1),
  interval_p10          NUMBER(12,10),
  interval_p90          NUMBER(12,10),
  covered               CHAR(1),
  eta_year              NUMBER(8,2),
  fold_status           VARCHAR2(24) NOT NULL,
  created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT pk_backtest_fold PRIMARY KEY (run_id, fold_index, pillar),
  CONSTRAINT ck_backtest_fold_index CHECK (fold_index >= 0),
  CONSTRAINT ck_backtest_fold_cohort CHECK (
    cohort IN ('CALIBRATION','HOLDOUT')),
  CONSTRAINT ck_backtest_fold_dates CHECK (target_date > cutoff_date),
  CONSTRAINT ck_backtest_fold_pillar CHECK (pillar BETWEEN 1 AND 6),
  CONSTRAINT ck_backtest_fold_current CHECK (
    current_readiness IS NULL OR current_readiness BETWEEN 0 AND 1),
  CONSTRAINT ck_backtest_fold_predicted CHECK (
    predicted_readiness IS NULL OR predicted_readiness BETWEEN 0 AND 1),
  CONSTRAINT ck_backtest_fold_actual CHECK (
    actual_readiness IS NULL OR actual_readiness BETWEEN 0 AND 1),
  CONSTRAINT ck_backtest_fold_interval CHECK (
    (interval_p10 IS NULL AND interval_p90 IS NULL)
    OR (interval_p10 BETWEEN 0 AND 1
      AND interval_p90 BETWEEN 0 AND 1
      AND interval_p10 <= interval_p90)),
  CONSTRAINT ck_backtest_fold_direction CHECK (
    predicted_advance IS NULL OR predicted_advance IN ('Y','N')),
  CONSTRAINT ck_backtest_fold_actual_direction CHECK (
    actual_advance IS NULL OR actual_advance IN ('Y','N')),
  CONSTRAINT ck_backtest_fold_covered CHECK (
    covered IS NULL OR covered IN ('Y','N')),
  CONSTRAINT ck_backtest_fold_status CHECK (
    fold_status IN ('OK','INSUFFICIENT_DATA')),
  CONSTRAINT ck_backtest_fold_complete CHECK (
    fold_status = 'INSUFFICIENT_DATA'
    OR (current_readiness IS NOT NULL
      AND predicted_readiness IS NOT NULL
      AND actual_readiness IS NOT NULL
      AND predicted_logit IS NOT NULL
      AND actual_logit IS NOT NULL
      AND predicted_advance IS NOT NULL
      AND actual_advance IS NOT NULL
      AND interval_p10 IS NOT NULL
      AND interval_p90 IS NOT NULL
      AND covered IS NOT NULL))
);

CREATE INDEX ix_backtest_fold_cohort
  ON backtest_fold(run_id, cohort, cutoff_date, pillar);

CREATE TABLE backtest_metric (
  run_id                 NUMBER NOT NULL REFERENCES backtest_run(id),
  metric_code            VARCHAR2(40) NOT NULL,
  pillar                 NUMBER(1) NOT NULL,
  calibration_value      NUMBER(18,12),
  holdout_value          NUMBER(18,12),
  calibration_samples    NUMBER(6) DEFAULT 0 NOT NULL,
  holdout_samples        NUMBER(6) DEFAULT 0 NOT NULL,
  calibration_status     VARCHAR2(24) NOT NULL,
  holdout_status         VARCHAR2(24) NOT NULL,
  created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT pk_backtest_metric PRIMARY KEY (run_id, metric_code, pillar),
  CONSTRAINT ck_backtest_metric_code CHECK (metric_code IN
    ('READINESS_MAE','LOGIT_READINESS_MAE','DIRECTION_ACCURACY',
     'INTERVAL_80_COVERAGE','ETA_VOLATILITY_YEARS')),
  CONSTRAINT ck_backtest_metric_pillar CHECK (pillar BETWEEN 0 AND 6),
  CONSTRAINT ck_backtest_metric_values CHECK (
    (calibration_value IS NULL OR calibration_value >= 0)
    AND (holdout_value IS NULL OR holdout_value >= 0)),
  CONSTRAINT ck_backtest_metric_samples CHECK (
    calibration_samples >= 0 AND holdout_samples >= 0),
  CONSTRAINT ck_backtest_metric_cal_status CHECK (
    calibration_status IN ('OK','INSUFFICIENT_DATA')),
  CONSTRAINT ck_backtest_metric_hold_status CHECK (
    holdout_status IN ('OK','INSUFFICIENT_DATA')),
  CONSTRAINT ck_backtest_metric_cal_complete CHECK (
    (calibration_status = 'OK'
      AND calibration_value IS NOT NULL
      AND calibration_samples > 0)
    OR (calibration_status = 'INSUFFICIENT_DATA'
      AND calibration_value IS NULL
      AND calibration_samples = 0)),
  CONSTRAINT ck_backtest_metric_hold_complete CHECK (
    (holdout_status = 'OK'
      AND holdout_value IS NOT NULL
      AND holdout_samples > 0)
    OR (holdout_status = 'INSUFFICIENT_DATA'
      AND holdout_value IS NULL
      AND holdout_samples = 0))
);

COMMENT ON TABLE backtest_run IS
  'Immutable WP4.4 calibration/holdout run and byte-stable public report';
COMMENT ON TABLE backtest_fold IS
  'Selected-model cutoff-local twelve-month hindcast observations';
COMMENT ON TABLE backtest_metric IS
  'Calibration and locked-holdout metrics with explicit sample sufficiency';
