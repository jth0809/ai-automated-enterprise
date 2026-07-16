-- Phase 3 WP3.4: reviewed forecast references and identified crowd history.
-- The pre-created external_forecast table remains the numeric observation
-- ledger. Undated institutional targets live only in forecast_reference.

CREATE TABLE forecast_reference (
  forecast_key       VARCHAR2(100) PRIMARY KEY,
  source_type        VARCHAR2(15) NOT NULL,
  source_name        VARCHAR2(100) NOT NULL,
  track_code         VARCHAR2(20) NOT NULL,
  question           VARCHAR2(500) NOT NULL,
  target_definition  VARCHAR2(1000) NOT NULL,
  display_status     VARCHAR2(30) NOT NULL,
  forecast_year      NUMBER(6,1),
  forecast_year_low  NUMBER(6,1),
  forecast_year_high NUMBER(6,1),
  relation_kind      VARCHAR2(20) NOT NULL,
  source_url         VARCHAR2(1000) NOT NULL,
  source_locator     VARCHAR2(300),
  accessed_on        DATE NOT NULL,
  ingestion_mode     VARCHAR2(20) NOT NULL,
  content_sha256     CHAR(64) NOT NULL,
  fact_summary       VARCHAR2(1000) NOT NULL,
  dataset_version    VARCHAR2(80) NOT NULL,
  created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_forecast_reference_source_type CHECK
    (source_type IN ('CROWD','INSTITUTIONAL')),
  CONSTRAINT ck_forecast_reference_track CHECK
    (track_code IN ('LANDING','RETURN','SETTLEMENT')),
  CONSTRAINT ck_forecast_reference_display CHECK
    (display_status IN
      ('CURRENT','UNDATED','PRECURSOR','LEGACY','AWAITING_AUTHORIZATION')),
  CONSTRAINT ck_forecast_reference_relation CHECK
    (relation_kind IN
      ('DIRECT','PROXY','SUPPORTING','PRECURSOR','REQUIREMENT')),
  CONSTRAINT ck_forecast_reference_ingestion CHECK
    (ingestion_mode IN ('REVIEWED_REFERENCE','API')),
  CONSTRAINT ck_forecast_reference_year CHECK
    (forecast_year IS NULL OR forecast_year BETWEEN 2026.0 AND 2300.0),
  CONSTRAINT ck_forecast_reference_low CHECK
    (forecast_year_low IS NULL OR forecast_year_low BETWEEN 2026.0 AND 2300.0),
  CONSTRAINT ck_forecast_reference_high CHECK
    (forecast_year_high IS NULL OR forecast_year_high BETWEEN 2026.0 AND 2300.0),
  CONSTRAINT ck_forecast_reference_range CHECK
    (forecast_year_low IS NULL OR forecast_year_high IS NULL
      OR forecast_year_low <= forecast_year_high),
  CONSTRAINT ck_forecast_reference_sha CHECK
    (LENGTH(content_sha256) = 64)
);

CREATE INDEX ix_forecast_reference_track
  ON forecast_reference(track_code, source_type, source_name);

CREATE TABLE forecast_reference_import (
  dataset_version VARCHAR2(80) PRIMARY KEY,
  dataset_sha256  CHAR(64) NOT NULL UNIQUE,
  record_count    NUMBER(3) NOT NULL,
  loaded_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_forecast_reference_import_count CHECK
    (record_count BETWEEN 1 AND 50),
  CONSTRAINT ck_forecast_reference_import_sha CHECK
    (LENGTH(dataset_sha256) = 64)
);

ALTER TABLE external_forecast ADD forecast_key VARCHAR2(100)
  REFERENCES forecast_reference(forecast_key);
ALTER TABLE external_forecast ADD observation_sha256 CHAR(64);
ALTER TABLE external_forecast ADD observation_status VARCHAR2(30);
ALTER TABLE external_forecast ADD smoothing_window_days NUMBER(3);

ALTER TABLE external_forecast ADD CONSTRAINT ck_external_forecast_status
  CHECK (observation_status IS NULL OR observation_status = 'CURRENT');
ALTER TABLE external_forecast ADD CONSTRAINT ck_external_forecast_window
  CHECK (smoothing_window_days IS NULL
    OR smoothing_window_days BETWEEN 1 AND 365);
ALTER TABLE external_forecast ADD CONSTRAINT ck_external_forecast_identified
  CHECK (forecast_key IS NULL OR
    (observation_sha256 IS NOT NULL
      AND observation_status = 'CURRENT'
      AND smoothing_window_days IS NOT NULL));

CREATE UNIQUE INDEX uq_external_forecast_identified
  ON external_forecast(forecast_key, retrieved_on);

COMMENT ON TABLE forecast_reference IS
  'Reviewed external forecast definitions and institutional target provenance';
COMMENT ON COLUMN external_forecast.smoothed_year IS
  'Arithmetic mean of identified crowd observations in the configured window';
