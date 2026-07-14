-- Phase 2 golden-set quality ledger and bounded in-process operations metrics.
-- Golden fixtures contain only authored SYNTHETIC or HUMAN_PARAPHRASE text.
-- No external source body, quote, excerpt, HTML, PDF, attachment, or raw model
-- output is stored by these tables.

CREATE TABLE golden_set_dataset (
  dataset_version       VARCHAR2(80) PRIMARY KEY,
  dataset_sha256        CHAR(64) NOT NULL,
  item_count            NUMBER(3) NOT NULL,
  loaded_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_golden_dataset_sha UNIQUE (dataset_sha256),
  CONSTRAINT ck_golden_dataset_count CHECK (item_count BETWEEN 1 AND 60)
);

ALTER TABLE golden_set_item ADD case_code VARCHAR2(80) NOT NULL;
ALTER TABLE golden_set_item ADD fixture_kind VARCHAR2(24) NOT NULL;
ALTER TABLE golden_set_item ADD expected_schema_version VARCHAR2(40) NOT NULL;
ALTER TABLE golden_set_item ADD rubric_version_id NUMBER NOT NULL;
ALTER TABLE golden_set_item ADD dataset_version VARCHAR2(80) NOT NULL;
ALTER TABLE golden_set_item ADD provenance_refs VARCHAR2(2000) DEFAULT '[]' NOT NULL;
ALTER TABLE golden_set_item ADD input_sha256 CHAR(64) NOT NULL;
ALTER TABLE golden_set_item ADD active CHAR(1) DEFAULT 'Y' NOT NULL;
ALTER TABLE golden_set_item ADD updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
ALTER TABLE golden_set_item ADD CONSTRAINT uq_golden_item_case UNIQUE (case_code);
ALTER TABLE golden_set_item ADD CONSTRAINT ck_golden_item_kind
  CHECK (fixture_kind IN ('SYNTHETIC','HUMAN_PARAPHRASE'));
ALTER TABLE golden_set_item ADD CONSTRAINT ck_golden_item_active
  CHECK (active IN ('Y','N'));
ALTER TABLE golden_set_item ADD CONSTRAINT fk_golden_item_rubric
  FOREIGN KEY (rubric_version_id) REFERENCES rubric_version(id);
ALTER TABLE golden_set_item ADD CONSTRAINT fk_golden_item_dataset
  FOREIGN KEY (dataset_version) REFERENCES golden_set_dataset(dataset_version);

COMMENT ON COLUMN golden_set_item.body IS
  'SYNTHETIC or HUMAN_PARAPHRASE authored fixture input only; NO EXTERNAL SOURCE BODY';

CREATE INDEX ix_golden_item_active
  ON golden_set_item(dataset_version, active, case_code);

CREATE TABLE golden_set_run (
  id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  mode                    VARCHAR2(20) NOT NULL,
  dataset_version         VARCHAR2(80) NOT NULL,
  prompt_version          VARCHAR2(80) NOT NULL,
  model_version           VARCHAR2(120) NOT NULL,
  rubric_version_id       NUMBER NOT NULL,
  expected_schema_version VARCHAR2(40) NOT NULL,
  run_status              VARCHAR2(12) NOT NULL,
  total_count             NUMBER(3) DEFAULT 0 NOT NULL,
  matched_count           NUMBER(3) DEFAULT 0 NOT NULL,
  failed_count            NUMBER(3) DEFAULT 0 NOT NULL,
  agreement               NUMBER(6,5),
  started_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  completed_at            TIMESTAMP,
  CONSTRAINT fk_golden_run_dataset FOREIGN KEY (dataset_version)
    REFERENCES golden_set_dataset(dataset_version),
  CONSTRAINT fk_golden_run_rubric FOREIGN KEY (rubric_version_id)
    REFERENCES rubric_version(id),
  CONSTRAINT ck_golden_run_mode CHECK
    (mode IN ('OFFLINE_REPLAY','LIVE_MODEL','DRILL')),
  CONSTRAINT ck_golden_run_status CHECK
    (run_status IN ('RUNNING','SUCCEEDED','FAILED','SKIPPED')),
  CONSTRAINT ck_golden_run_counts CHECK
    (total_count BETWEEN 0 AND 60
     AND matched_count BETWEEN 0 AND total_count
     AND failed_count BETWEEN 0 AND total_count
     AND matched_count + failed_count <= total_count),
  CONSTRAINT ck_golden_run_agreement CHECK
    (agreement IS NULL OR agreement BETWEEN 0 AND 1)
);

CREATE INDEX ix_golden_run_started
  ON golden_set_run(started_at, run_status);

CREATE TABLE golden_set_result (
  id                   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id               NUMBER NOT NULL,
  item_id              NUMBER NOT NULL,
  actual_output_sha256 CHAR(64),
  matched              CHAR(1) NOT NULL,
  mismatch_fields      VARCHAR2(1000),
  error_code           VARCHAR2(80),
  created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT fk_golden_result_run FOREIGN KEY (run_id)
    REFERENCES golden_set_run(id),
  CONSTRAINT fk_golden_result_item FOREIGN KEY (item_id)
    REFERENCES golden_set_item(id),
  CONSTRAINT uq_golden_result_run_item UNIQUE (run_id, item_id),
  CONSTRAINT ck_golden_result_match CHECK (matched IN ('Y','N')),
  CONSTRAINT ck_golden_result_payload CHECK
    ((matched = 'Y' AND mismatch_fields IS NULL AND error_code IS NULL)
     OR matched = 'N')
);

CREATE INDEX ix_golden_result_run
  ON golden_set_result(run_id, matched);

CREATE TABLE pipeline_metric_daily (
  id                     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  metric_date            DATE NOT NULL,
  metric_code            VARCHAR2(40) NOT NULL,
  metric_value           NUMBER(18,6) NOT NULL,
  baseline_mean          NUMBER(18,6),
  lower_bound            NUMBER(18,6),
  upper_bound            NUMBER(18,6),
  monitor_status         VARCHAR2(20) NOT NULL,
  violation              CHAR(1) DEFAULT 'N' NOT NULL,
  consecutive_violations NUMBER(2) DEFAULT 0 NOT NULL,
  sample_days            NUMBER(2) DEFAULT 0 NOT NULL,
  created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_pipeline_metric_day UNIQUE (metric_date, metric_code),
  CONSTRAINT ck_pipeline_metric_code CHECK
    (metric_code IN (
      'RELEVANCE_GATE_PASS_RATE','CONFIRMED_EVENT_COUNT',
      'IMPACT_MEDIAN','IMPACT_P95')),
  CONSTRAINT ck_pipeline_metric_value CHECK
    (metric_value >= 0
     AND (metric_code <> 'RELEVANCE_GATE_PASS_RATE' OR metric_value <= 1)),
  CONSTRAINT ck_pipeline_metric_status CHECK
    (monitor_status IN ('INSUFFICIENT_DATA','OK','WARNING','TRIGGERED')),
  CONSTRAINT ck_pipeline_metric_violation CHECK (violation IN ('Y','N')),
  CONSTRAINT ck_pipeline_metric_counts CHECK
    (consecutive_violations BETWEEN 0 AND 28 AND sample_days BETWEEN 0 AND 28),
  CONSTRAINT ck_pipeline_metric_bounds CHECK
    (lower_bound IS NULL OR upper_bound IS NULL OR lower_bound <= upper_bound)
);

CREATE INDEX ix_pipeline_metric_code_date
  ON pipeline_metric_daily(metric_code, metric_date);

CREATE TABLE ops_action_log (
  id             NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  action_type    VARCHAR2(12) NOT NULL,
  reason         VARCHAR2(1000) NOT NULL,
  trigger_type   VARCHAR2(12) NOT NULL,
  previous_state VARCHAR2(12) NOT NULL,
  new_state      VARCHAR2(12) NOT NULL,
  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_ops_action_type CHECK (action_type IN ('FREEZE','RELEASE')),
  CONSTRAINT ck_ops_trigger_type CHECK
    (trigger_type IN ('AUTOMATIC','HUMAN','DRILL')),
  CONSTRAINT ck_ops_state_values CHECK
    (previous_state IN ('ACTIVE','FROZEN') AND new_state IN ('ACTIVE','FROZEN')),
  CONSTRAINT ck_ops_state_transition CHECK
    ((action_type = 'FREEZE' AND new_state = 'FROZEN')
     OR (action_type = 'RELEASE' AND previous_state = 'FROZEN'
         AND new_state = 'ACTIVE' AND trigger_type = 'HUMAN')),
  CONSTRAINT ck_ops_reason_nonblank CHECK (LENGTH(TRIM(reason)) > 0)
);

CREATE INDEX ix_ops_action_created
  ON ops_action_log(created_at, action_type);
