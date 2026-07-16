-- Phase 3 WP3.3: audited transport-economics assumptions, observations,
-- projections, and non-destructive Layer B/Layer C coherence reports.
-- Source content is never stored: only numeric facts and provenance metadata.

CREATE TABLE transport_economics_assumption (
  assumption_version VARCHAR2(80) PRIMARY KEY,
  model_version VARCHAR2(80) NOT NULL,
  central_target_usd_per_kg NUMBER(14,4) NOT NULL,
  easy_target_usd_per_kg NUMBER(14,4) NOT NULL,
  hard_target_usd_per_kg NUMBER(14,4) NOT NULL,
  price_basis_year NUMBER(4) NOT NULL,
  horizon_years NUMBER(3) NOT NULL,
  weak_fit_r2 NUMBER(6,5) NOT NULL,
  widening_factor NUMBER(5,2) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_transport_assumption_targets CHECK (
    hard_target_usd_per_kg > 0
    AND hard_target_usd_per_kg < central_target_usd_per_kg
    AND central_target_usd_per_kg < easy_target_usd_per_kg),
  CONSTRAINT ck_transport_assumption_basis CHECK (price_basis_year BETWEEN 2000 AND 9999),
  CONSTRAINT ck_transport_assumption_horizon CHECK (horizon_years BETWEEN 1 AND 150),
  CONSTRAINT ck_transport_assumption_r2 CHECK (weak_fit_r2 BETWEEN 0 AND 1),
  CONSTRAINT ck_transport_assumption_widen CHECK (widening_factor >= 1)
);

CREATE TABLE transport_price_observation (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  observation_year NUMBER(4) NOT NULL,
  vehicle_family VARCHAR2(40) NOT NULL,
  vehicle_variant VARCHAR2(80) NOT NULL,
  published_price_usd NUMBER(18,2) NOT NULL,
  max_leo_payload_kg NUMBER(14,2) NOT NULL,
  nominal_usd_per_kg NUMBER(14,4) NOT NULL,
  cpi_observation_value NUMBER(10,3) NOT NULL,
  cpi_basis_value NUMBER(10,3) NOT NULL,
  real_basis_usd_per_kg NUMBER(14,4) NOT NULL,
  cumulative_family_launches NUMBER(8) NOT NULL,
  source_label VARCHAR2(200) NOT NULL,
  source_url VARCHAR2(600) NOT NULL,
  source_locator VARCHAR2(300) NOT NULL,
  accessed_on DATE NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  fact_summary VARCHAR2(500) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_transport_price_natural UNIQUE
    (observation_year, vehicle_family, vehicle_variant),
  CONSTRAINT ck_transport_price_year CHECK (observation_year BETWEEN 2000 AND 9999),
  CONSTRAINT ck_transport_price_family CHECK (vehicle_family = 'FALCON'),
  CONSTRAINT ck_transport_price_positive CHECK (
    published_price_usd > 0
    AND max_leo_payload_kg > 0
    AND nominal_usd_per_kg > 0
    AND cpi_observation_value > 0
    AND cpi_basis_value > 0
    AND real_basis_usd_per_kg > 0
    AND cumulative_family_launches > 0)
);

CREATE INDEX ix_transport_price_year
  ON transport_price_observation(observation_year, real_basis_usd_per_kg);

CREATE TABLE transport_economics_projection (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  as_of_date DATE NOT NULL,
  assumption_version VARCHAR2(80) NOT NULL
    REFERENCES transport_economics_assumption(assumption_version),
  model_version VARCHAR2(80) NOT NULL,
  status VARCHAR2(30) NOT NULL,
  sufficiency_tier VARCHAR2(30) NOT NULL,
  qualification_flags VARCHAR2(300) NOT NULL,
  observation_count NUMBER(4) NOT NULL,
  alpha NUMBER(20,10),
  beta NUMBER(20,10),
  r_squared NUMBER(12,10),
  current_cumulative_launches NUMBER(8) NOT NULL,
  central_cadence NUMBER(12,4),
  fast_cadence NUMBER(12,4),
  slow_cadence NUMBER(12,4),
  central_target_usd_per_kg NUMBER(14,4) NOT NULL,
  easy_target_usd_per_kg NUMBER(14,4) NOT NULL,
  hard_target_usd_per_kg NUMBER(14,4) NOT NULL,
  central_required_launches NUMBER(20,4),
  easy_required_launches NUMBER(20,4),
  hard_required_launches NUMBER(20,4),
  central_eta_year NUMBER(7,1),
  earliest_eta_year NUMBER(7,1),
  latest_eta_year NUMBER(7,1),
  central_beyond_horizon CHAR(1) NOT NULL,
  earliest_beyond_horizon CHAR(1) NOT NULL,
  latest_beyond_horizon CHAR(1) NOT NULL,
  price_basis_year NUMBER(4) NOT NULL,
  horizon_years NUMBER(3) NOT NULL,
  interval_kind VARCHAR2(40) NOT NULL,
  basis VARCHAR2(30) NOT NULL,
  price_meaning VARCHAR2(120) NOT NULL,
  projection_label VARCHAR2(200) NOT NULL,
  reason_code VARCHAR2(80) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_transport_projection UNIQUE (as_of_date, assumption_version),
  CONSTRAINT ck_transport_projection_status CHECK (status IN
    ('INSUFFICIENT_DATA','PROVISIONAL','ESTABLISHED','NON_DECLINING',
     'REACHED','BEYOND_HORIZON')),
  CONSTRAINT ck_transport_projection_tier CHECK (sufficiency_tier IN
    ('INSUFFICIENT_DATA','PROVISIONAL','ESTABLISHED')),
  CONSTRAINT ck_transport_projection_counts CHECK (
    observation_count >= 0 AND current_cumulative_launches >= 0),
  CONSTRAINT ck_transport_projection_r2 CHECK (r_squared IS NULL OR r_squared BETWEEN 0 AND 1),
  CONSTRAINT ck_transport_projection_horizon CHECK (horizon_years BETWEEN 1 AND 150),
  CONSTRAINT ck_transport_projection_flags CHECK (
    central_beyond_horizon IN ('Y','N')
    AND earliest_beyond_horizon IN ('Y','N')
    AND latest_beyond_horizon IN ('Y','N')),
  CONSTRAINT ck_transport_projection_interval CHECK (
    interval_kind = 'ASSUMPTION_SENSITIVITY'),
  CONSTRAINT ck_transport_projection_basis CHECK (basis = 'PUBLISHED_PRICE')
);

CREATE INDEX ix_transport_projection_latest
  ON transport_economics_projection(as_of_date, id);

CREATE TABLE transport_coherence_report (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  report_period_end DATE NOT NULL UNIQUE,
  layer_c_snapshot_date DATE,
  price_direction VARCHAR2(20) NOT NULL,
  cadence_direction VARCHAR2(20) NOT NULL,
  layer_b_direction VARCHAR2(20) NOT NULL,
  layer_c_direction VARCHAR2(20) NOT NULL,
  coherence_state VARCHAR2(30) NOT NULL,
  polarity VARCHAR2(20) NOT NULL,
  consecutive_quarter_streak NUMBER(3) NOT NULL,
  alert_active CHAR(1) NOT NULL,
  widening_factor NUMBER(5,2) NOT NULL,
  first_divergent_period DATE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_transport_coherence_price_dir CHECK (
    price_direction IN ('ADVANCING','REGRESSING','FLAT','INSUFFICIENT_DATA')),
  CONSTRAINT ck_transport_coherence_cadence_dir CHECK (
    cadence_direction IN ('ADVANCING','REGRESSING','FLAT','INSUFFICIENT_DATA')),
  CONSTRAINT ck_transport_coherence_layer_b CHECK (
    layer_b_direction IN ('ADVANCING','REGRESSING','FLAT','MIXED','INSUFFICIENT_DATA')),
  CONSTRAINT ck_transport_coherence_layer_c CHECK (
    layer_c_direction IN ('ADVANCING','REGRESSING','FLAT','INSUFFICIENT_DATA')),
  CONSTRAINT ck_transport_coherence_state CHECK (coherence_state IN
    ('INSUFFICIENT_DATA','COHERENT','WATCH','DIVERGENT','MIXED')),
  CONSTRAINT ck_transport_coherence_polarity CHECK (
    polarity IN ('NONE','B_AHEAD','C_AHEAD')),
  CONSTRAINT ck_transport_coherence_streak CHECK (consecutive_quarter_streak >= 0),
  CONSTRAINT ck_transport_coherence_alert CHECK (
    (alert_active = 'Y' AND coherence_state = 'DIVERGENT' AND widening_factor = 1.50)
    OR (alert_active = 'N' AND widening_factor = 1.00))
);

CREATE INDEX ix_transport_coherence_latest
  ON transport_coherence_report(report_period_end, id);

CREATE TABLE transport_coherence_sample (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  report_id NUMBER NOT NULL REFERENCES transport_coherence_report(id),
  event_id NUMBER NOT NULL REFERENCES event(id),
  review_status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
  reviewer_note VARCHAR2(2000),
  reviewed_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_transport_coherence_sample UNIQUE (report_id, event_id),
  CONSTRAINT ck_transport_sample_status CHECK (review_status IN ('PENDING','REVIEWED')),
  CONSTRAINT ck_transport_sample_review CHECK (
    (review_status = 'PENDING' AND reviewer_note IS NULL AND reviewed_at IS NULL)
    OR (review_status = 'REVIEWED' AND reviewer_note IS NOT NULL AND reviewed_at IS NOT NULL))
);

CREATE INDEX ix_transport_sample_report
  ON transport_coherence_sample(report_id, review_status, id);

CREATE TABLE transport_economics_import (
  dataset_version VARCHAR2(80) PRIMARY KEY,
  dataset_sha256 CHAR(64) NOT NULL UNIQUE,
  price_observation_count NUMBER(5) NOT NULL,
  annual_launch_record_count NUMBER(5) NOT NULL,
  cpi_record_count NUMBER(5) NOT NULL,
  loaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_transport_import_counts CHECK (
    price_observation_count > 0
    AND annual_launch_record_count >= 3
    AND cpi_record_count >= 2)
);

COMMENT ON COLUMN transport_price_observation.fact_summary IS
  'Reviewer-authored numeric fact summary; NO SOURCE BODY, QUOTE, HTML, PDF, IMAGE, OR BINARY';
COMMENT ON COLUMN transport_economics_projection.interval_kind IS
  'ASSUMPTION_SENSITIVITY, not a probability interval';
