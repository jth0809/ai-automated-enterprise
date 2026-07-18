-- Forecast credibility corrections: append-only model-role benchmarks and
-- persisted historical candidate-corpus cardinality. Existing v1 runs remain
-- valid and receive no fabricated evaluation rows.

ALTER TABLE backfill_import ADD candidate_record_count NUMBER(5);

ALTER TABLE backfill_import ADD CONSTRAINT ck_backfill_candidate_count
  CHECK (candidate_record_count IS NULL
    OR candidate_record_count BETWEEN 1 AND 99999);

CREATE TABLE backtest_model_evaluation (
  run_id                 NUMBER NOT NULL REFERENCES backtest_run(id),
  model_role             VARCHAR2(20) NOT NULL,
  metric_code            VARCHAR2(40) NOT NULL,
  pillar                 NUMBER(1) NOT NULL,
  window_m               NUMBER(2),
  k_shrink               NUMBER(8,3),
  delta_scale            NUMBER(4,2),
  calibration_value      NUMBER(18,12),
  holdout_value          NUMBER(18,12),
  calibration_samples    NUMBER(6) DEFAULT 0 NOT NULL,
  holdout_samples        NUMBER(6) DEFAULT 0 NOT NULL,
  calibration_status     VARCHAR2(24) NOT NULL,
  holdout_status         VARCHAR2(24) NOT NULL,
  created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT pk_backtest_model_evaluation PRIMARY KEY
    (run_id, model_role, metric_code, pillar),
  CONSTRAINT ck_backtest_eval_role CHECK (model_role IN
    ('SELECTED','ACTIVE','PERSISTENCE','ALWAYS_NO_CHANGE')),
  CONSTRAINT ck_backtest_eval_metric CHECK (metric_code IN
    ('READINESS_MAE','LOGIT_READINESS_MAE','DIRECTION_ACCURACY',
     'INTERVAL_80_COVERAGE','ETA_VOLATILITY_YEARS')),
  CONSTRAINT ck_backtest_eval_pillar CHECK (pillar BETWEEN 0 AND 6),
  CONSTRAINT ck_backtest_eval_candidate CHECK (
    (model_role IN ('SELECTED','ACTIVE')
      AND window_m IN (4,6,8)
      AND k_shrink IN (2,4,8)
      AND delta_scale IN (0.75,1.00,1.25))
    OR (model_role IN ('PERSISTENCE','ALWAYS_NO_CHANGE')
      AND window_m IS NULL AND k_shrink IS NULL AND delta_scale IS NULL)),
  CONSTRAINT ck_backtest_eval_role_metric CHECK (
    model_role IN ('SELECTED','ACTIVE')
    OR (model_role = 'PERSISTENCE'
      AND metric_code IN ('READINESS_MAE','LOGIT_READINESS_MAE'))
    OR (model_role = 'ALWAYS_NO_CHANGE'
      AND metric_code = 'DIRECTION_ACCURACY')),
  CONSTRAINT ck_backtest_eval_values CHECK (
    (calibration_value IS NULL OR calibration_value >= 0)
    AND (holdout_value IS NULL OR holdout_value >= 0)),
  CONSTRAINT ck_backtest_eval_samples CHECK (
    calibration_samples >= 0 AND holdout_samples >= 0),
  CONSTRAINT ck_backtest_eval_cal_status CHECK (
    calibration_status IN ('OK','INSUFFICIENT_DATA')),
  CONSTRAINT ck_backtest_eval_hold_status CHECK (
    holdout_status IN ('OK','INSUFFICIENT_DATA')),
  CONSTRAINT ck_backtest_eval_cal_complete CHECK (
    (calibration_status = 'OK'
      AND calibration_value IS NOT NULL AND calibration_samples > 0)
    OR (calibration_status = 'INSUFFICIENT_DATA'
      AND calibration_value IS NULL AND calibration_samples = 0)),
  CONSTRAINT ck_backtest_eval_hold_complete CHECK (
    (holdout_status = 'OK'
      AND holdout_value IS NOT NULL AND holdout_samples > 0)
    OR (holdout_status = 'INSUFFICIENT_DATA'
      AND holdout_value IS NULL AND holdout_samples = 0))
);

CREATE INDEX ix_backtest_model_evaluation_role
  ON backtest_model_evaluation(run_id, model_role, pillar);

COMMENT ON TABLE backtest_model_evaluation IS
  'Versioned selected, active, persistence, and no-change backtest metrics';
