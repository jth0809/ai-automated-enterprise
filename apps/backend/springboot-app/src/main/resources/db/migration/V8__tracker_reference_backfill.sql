-- V8__tracker_reference_backfill.sql
--
-- Historical evidence is reference-only. No source body, quote, excerpt,
-- source title, HTML, PDF, image, or attachment column is permitted here.

CREATE TABLE historical_evidence (
  id                     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  backfill_id            VARCHAR2(80) NOT NULL,
  candidate_id           VARCHAR2(80) NOT NULL,
  occurred_on_precision  VARCHAR2(5) NOT NULL,
  event_id               NUMBER NOT NULL,
  source_id              NUMBER NOT NULL,
  url                    VARCHAR2(1000) NOT NULL,
  locator                VARCHAR2(300) NOT NULL,
  accessed_on            DATE NOT NULL,
  content_sha256         CHAR(64) NOT NULL,
  publication_path       VARCHAR2(20) NOT NULL,
  fact_summary           VARCHAR2(500) NOT NULL,
  fact_review_status     VARCHAR2(10) NOT NULL,
  rubric_review_status   VARCHAR2(10) NOT NULL,
  reference_status       VARCHAR2(10) DEFAULT 'APPROVED' NOT NULL,
  reviewer_note          VARCHAR2(2000),
  created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT fk_hist_evidence_event FOREIGN KEY (event_id) REFERENCES event(id),
  CONSTRAINT fk_hist_evidence_source FOREIGN KEY (source_id) REFERENCES source_registry(id),
  CONSTRAINT ck_hist_date_precision CHECK
    (occurred_on_precision IN ('DAY','MONTH','YEAR')),
  CONSTRAINT ck_hist_publication_path CHECK
    (publication_path IN ('PRIMARY','THIRD_PARTY','WIRE_REPRINT')),
  CONSTRAINT ck_hist_fact_review CHECK
    (fact_review_status IN ('APPROVED','REJECTED')),
  CONSTRAINT ck_hist_rubric_review CHECK
    (rubric_review_status IN ('APPROVED','REJECTED')),
  CONSTRAINT ck_hist_reference_status CHECK
    (reference_status IN ('APPROVED','STALE','REJECTED')),
  CONSTRAINT uq_historical_evidence UNIQUE (backfill_id, source_id, url)
);

CREATE INDEX ix_hist_evidence_event ON historical_evidence(event_id);
CREATE INDEX ix_hist_evidence_candidate ON historical_evidence(candidate_id);

CREATE TABLE backfill_import (
  dataset_version        VARCHAR2(40) PRIMARY KEY,
  dataset_sha256         CHAR(64) NOT NULL,
  node_set_version       VARCHAR2(40) NOT NULL,
  rubric_version_id      NUMBER NOT NULL,
  imported_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  record_count           NUMBER(5) NOT NULL,
  CONSTRAINT fk_bf_import_rubric FOREIGN KEY (rubric_version_id) REFERENCES rubric_version(id),
  CONSTRAINT uq_backfill_import_sha UNIQUE (dataset_sha256),
  CONSTRAINT ck_backfill_import_count CHECK (record_count BETWEEN 1 AND 99999)
);
