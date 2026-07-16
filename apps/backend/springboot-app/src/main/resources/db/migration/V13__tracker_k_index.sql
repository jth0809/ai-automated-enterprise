-- Phase 3 WP3.2: annual Kardashev K-index observations with provenance.
-- Existing V1 columns remain compatible; reviewed imports add source metadata.

ALTER TABLE k_index ADD (
  primary_energy_twh NUMBER(14,3),
  source_url VARCHAR2(1000),
  accessed_on DATE,
  dataset_version VARCHAR2(80)
);

CREATE TABLE k_index_import (
  dataset_version VARCHAR2(80) PRIMARY KEY,
  dataset_sha256 CHAR(64) NOT NULL UNIQUE,
  record_count NUMBER(4) NOT NULL,
  source_url VARCHAR2(1000) NOT NULL,
  accessed_on DATE NOT NULL,
  loaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_k_index_import_count CHECK (record_count BETWEEN 1 AND 200)
);

COMMENT ON COLUMN k_index.primary_energy_twh IS
  'Reviewed annual world primary-energy observation in TWh';
COMMENT ON COLUMN k_index.k_value IS
  'K=(log10(power_watts)-6)/10; display gauge only, no automatic readiness effect';
