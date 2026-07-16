-- Phase 3 Layer B: measured non-AI indicators (numeric only).
-- No external source body, quote, excerpt, HTML, PDF, image, or binary is
-- stored. Only numeric values plus provenance metadata and an authored summary.

CREATE TABLE layer_b_metric (
  id              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  metric_code     VARCHAR2(60) NOT NULL,
  pillar          NUMBER(1) NOT NULL,
  observed_on     DATE NOT NULL,
  metric_value    NUMBER(20,4) NOT NULL,
  unit            VARCHAR2(30) NOT NULL,
  basis           VARCHAR2(20) NOT NULL,
  source_label    VARCHAR2(200) NOT NULL,
  source_url      VARCHAR2(600) NOT NULL,
  accessed_on     DATE NOT NULL,
  content_sha256  CHAR(64) NOT NULL,
  fact_summary    VARCHAR2(500) NOT NULL,
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_layer_b_pillar CHECK (pillar BETWEEN 1 AND 6),
  CONSTRAINT ck_layer_b_basis CHECK (basis IN ('MEASURED','PUBLISHED_PRICE','CONSTRUCTED')),
  CONSTRAINT uq_layer_b_natural UNIQUE (metric_code, observed_on)
);

COMMENT ON COLUMN layer_b_metric.metric_value IS
  'Numeric indicator only; NO EXTERNAL SOURCE BODY, QUOTE, OR BINARY';
COMMENT ON COLUMN layer_b_metric.basis IS
  'MEASURED=observed count/rate; PUBLISHED_PRICE=list price estimate; CONSTRUCTED=composite index';

CREATE INDEX ix_layer_b_pillar ON layer_b_metric(pillar, metric_code, observed_on);

CREATE TABLE layer_b_metric_import (
  dataset_version  VARCHAR2(80) PRIMARY KEY,
  dataset_sha256   CHAR(64) NOT NULL,
  record_count     NUMBER(5) NOT NULL,
  loaded_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_layer_b_import_sha UNIQUE (dataset_sha256),
  CONSTRAINT ck_layer_b_import_count CHECK (record_count > 0)
);
