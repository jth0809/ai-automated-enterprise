-- Phase 4 WP4.2/WP4.3: version complete-trend parameters, reviewed regime
-- breaks, and the immutable projection run/result contract. Existing
-- parameter rows and snapshots are preserved.

UPDATE parameter_set SET active = 'N' WHERE active = 'Y';

INSERT INTO parameter_set (
  version_label, active, epsilon, k_shrink, window_m,
  window_fixed_years, window_min_years, window_max_years,
  dormancy_start, dormancy_step_per_decade, dormancy_floor,
  dormancy_trigger_years, default_delta_e,
  eta_clamp_min_years, eta_clamp_max_years,
  display_damping_days_per_day, daily_cost_cap_usd,
  trl_map, maturity_map
)
SELECT
  'params-v2', 'Y', epsilon, 4, 6,
  10, 4, 15,
  dormancy_start, dormancy_step_per_decade, dormancy_floor,
  dormancy_trigger_years, default_delta_e,
  eta_clamp_min_years, eta_clamp_max_years,
  display_damping_days_per_day, daily_cost_cap_usd,
  trl_map, maturity_map
  FROM parameter_set
 WHERE version_label = 'params-v1';

CREATE TABLE parameter_uncertainty (
  id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parameter_set_id  NUMBER NOT NULL REFERENCES parameter_set(id),
  parameter_name    VARCHAR2(80) NOT NULL,
  distribution_type VARCHAR2(24) NOT NULL,
  lower_value       NUMBER(16,8) NOT NULL,
  central_value     NUMBER(16,8) NOT NULL,
  upper_value       NUMBER(16,8) NOT NULL,
  scale_value       NUMBER(16,8),
  notes             VARCHAR2(1000) NOT NULL,
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_parameter_uncertainty UNIQUE (parameter_set_id, parameter_name),
  CONSTRAINT ck_uncertainty_distribution CHECK (distribution_type IN
    ('FIXED','DISCRETE','BOUNDED_NORMAL','DIRICHLET')),
  CONSTRAINT ck_uncertainty_order CHECK (
    lower_value <= central_value AND central_value <= upper_value),
  CONSTRAINT ck_uncertainty_scale CHECK (
    scale_value IS NULL OR scale_value > 0)
);

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'mc_samples', 'FIXED', 1000, 4000, 10000, NULL,
       'Default and allowed Monte Carlo sample-count range'
  FROM parameter_set WHERE version_label = 'params-v2';

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'trend_covariance_scale', 'BOUNDED_NORMAL', 0.50, 1.00, 1.50, 0.15,
       'Multiplier applied to fitted coefficient covariance draws'
  FROM parameter_set WHERE version_label = 'params-v2';

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'node_weight_concentration', 'DIRICHLET', 50, 200, 500, NULL,
       'Pillar-local positive weight concentration before renormalization'
  FROM parameter_set WHERE version_label = 'params-v2';

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'mapping_sigma', 'FIXED', 0.005, 0.015, 0.050, NULL,
       'Bounded TRL and EGL mapping perturbation width'
  FROM parameter_set WHERE version_label = 'params-v2';

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'delta_scale', 'DISCRETE', 0.75, 1.00, 1.25, NULL,
       'Shared capability-edge delta multiplier; edges are not independent parameters'
  FROM parameter_set WHERE version_label = 'params-v2';

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'k_log_sigma', 'FIXED', 0.10, 0.25, 0.50, NULL,
       'Log-normal shrinkage-strength perturbation width'
  FROM parameter_set WHERE version_label = 'params-v2';

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'dormancy_start', 'BOUNDED_NORMAL', 0.80, 0.85, 0.90, 0.02,
       'Dormancy initial multiplier uncertainty'
  FROM parameter_set WHERE version_label = 'params-v2';

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'dormancy_step_per_decade', 'BOUNDED_NORMAL', 0.10, 0.15, 0.20, 0.02,
       'Dormancy decade-step uncertainty'
  FROM parameter_set WHERE version_label = 'params-v2';

INSERT INTO parameter_uncertainty
  (parameter_set_id, parameter_name, distribution_type,
   lower_value, central_value, upper_value, scale_value, notes)
SELECT id, 'dormancy_floor', 'BOUNDED_NORMAL', 0.30, 0.40, 0.50, 0.04,
       'Dormancy floor uncertainty'
  FROM parameter_set WHERE version_label = 'params-v2';

CREATE TABLE model_regime_break (
  id              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  pillar          NUMBER(1) NOT NULL,
  break_date      DATE NOT NULL,
  cause_event_id  NUMBER NOT NULL REFERENCES event(id),
  review_status   VARCHAR2(12) NOT NULL,
  reviewer        VARCHAR2(120) NOT NULL,
  reviewer_note   VARCHAR2(2000) NOT NULL,
  params_version  VARCHAR2(40) NOT NULL REFERENCES parameter_set(version_label),
  approved_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_model_regime_break UNIQUE (pillar, break_date, params_version),
  CONSTRAINT ck_regime_break_pillar CHECK (pillar BETWEEN 1 AND 6),
  CONSTRAINT ck_regime_break_review CHECK (review_status IN ('APPROVED','RETIRED'))
);
CREATE INDEX ix_regime_break_asof
  ON model_regime_break(pillar, break_date, review_status);

CREATE TABLE projection_run (
  id                   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  input_sha256         CHAR(64) NOT NULL UNIQUE,
  seed_value           NUMBER(19) NOT NULL,
  sample_count         NUMBER(5) NOT NULL,
  params_version       VARCHAR2(40) NOT NULL REFERENCES parameter_set(version_label),
  graph_version        VARCHAR2(40) NOT NULL REFERENCES capability_graph_version(version_label),
  node_set_version     VARCHAR2(40) NOT NULL,
  dataset_sha256       CHAR(64) NOT NULL,
  run_status           VARCHAR2(12) NOT NULL,
  invalid_sample_count NUMBER(5) DEFAULT 0 NOT NULL,
  diagnostics          VARCHAR2(2000),
  current_result       CHAR(1) DEFAULT 'N' NOT NULL,
  started_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  completed_at         TIMESTAMP,
  CONSTRAINT ck_projection_run_samples CHECK (sample_count BETWEEN 1000 AND 10000),
  CONSTRAINT ck_projection_run_invalid CHECK (
    invalid_sample_count BETWEEN 0 AND sample_count),
  CONSTRAINT ck_projection_run_status CHECK (run_status IN ('RUNNING','COMPLETED','FAILED')),
  CONSTRAINT ck_projection_run_current CHECK (current_result IN ('Y','N')),
  CONSTRAINT ck_projection_current_complete CHECK (
    current_result = 'N' OR run_status = 'COMPLETED'),
  CONSTRAINT ck_projection_completion CHECK (
    (run_status = 'RUNNING' AND completed_at IS NULL)
    OR (run_status IN ('COMPLETED','FAILED') AND completed_at IS NOT NULL)),
  CONSTRAINT ck_projection_input_sha CHECK (LENGTH(input_sha256) = 64),
  CONSTRAINT ck_projection_dataset_sha CHECK (LENGTH(dataset_sha256) = 64)
);

CREATE TABLE projection_result (
  run_id             NUMBER NOT NULL REFERENCES projection_run(id),
  pillar             NUMBER(1) NOT NULL,
  readiness          NUMBER(6,5) NOT NULL,
  eta_p10            NUMBER(6,1),
  eta_p50            NUMBER(6,1),
  eta_p90            NUMBER(6,1),
  censored_fraction  NUMBER(8,7) NOT NULL,
  momentum           VARCHAR2(24) NOT NULL,
  created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT pk_projection_result PRIMARY KEY (run_id, pillar),
  CONSTRAINT ck_projection_result_pillar CHECK (pillar BETWEEN 0 AND 6),
  CONSTRAINT ck_projection_readiness CHECK (readiness BETWEEN 0 AND 1),
  CONSTRAINT ck_projection_censored CHECK (censored_fraction BETWEEN 0 AND 1),
  CONSTRAINT ck_projection_momentum CHECK (momentum IN
    ('ACCELERATING','STEADY','DECELERATING','INSUFFICIENT_DATA')),
  CONSTRAINT ck_projection_quantile_nulls CHECK (
    NOT (eta_p10 IS NULL AND (eta_p50 IS NOT NULL OR eta_p90 IS NOT NULL))
    AND NOT (eta_p50 IS NULL AND eta_p90 IS NOT NULL)),
  CONSTRAINT ck_projection_quantile_order CHECK (
    (eta_p10 IS NULL OR eta_p50 IS NULL OR eta_p10 <= eta_p50)
    AND (eta_p50 IS NULL OR eta_p90 IS NULL OR eta_p50 <= eta_p90))
);

COMMENT ON TABLE parameter_uncertainty IS
  'Versioned Phase 4 uncertainty widths and concentrations; no hidden Java defaults';
COMMENT ON TABLE model_regime_break IS
  'Human-reviewed structural breaks; 2010 holdout boundary is not a runtime break';
COMMENT ON TABLE projection_run IS
  'Atomic deterministic Monte Carlo run metadata; only completed runs may be current';
COMMENT ON TABLE projection_result IS
  'Per-pillar and overall right-censored projection quantiles';

