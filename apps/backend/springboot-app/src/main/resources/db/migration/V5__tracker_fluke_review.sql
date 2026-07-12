-- Phase 1b fluke filter and review audit schema (WP1.6).
-- One review row per (event, reason) trigger; the fluke filter records one
-- successful, version-stamped evaluation per review. Keep byte-compatible
-- with Oracle ATP and H2 in MODE=Oracle.

ALTER TABLE review_queue ADD priority NUMBER(1) DEFAULT 0 NOT NULL;
ALTER TABLE review_queue ADD fluke_status VARCHAR2(10) DEFAULT 'PENDING' NOT NULL;
ALTER TABLE review_queue ADD fluke_fail_count NUMBER(2) DEFAULT 0 NOT NULL;
ALTER TABLE review_queue ADD fluke_last_error VARCHAR2(1000);
ALTER TABLE review_queue ADD CONSTRAINT ck_review_priority
  CHECK (priority BETWEEN 0 AND 2);
ALTER TABLE review_queue ADD CONSTRAINT ck_review_fluke_status
  CHECK (fluke_status IN ('PENDING','COMPLETE','FAILED'));
CREATE UNIQUE INDEX uq_review_event_reason ON review_queue(event_id, reason);

CREATE TABLE fluke_evaluation (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  review_id NUMBER NOT NULL REFERENCES review_queue(id),
  event_id NUMBER NOT NULL REFERENCES event(id),
  verdict VARCHAR2(10) NOT NULL CHECK (verdict IN ('MATCH','MISMATCH')),
  evidence_quote CLOB NOT NULL,
  quote_verified CHAR(1) NOT NULL CHECK (quote_verified IN ('Y','N')),
  raw_output CLOB NOT NULL,
  model_id VARCHAR2(64) NOT NULL,
  prompt_sha256 CHAR(64) NOT NULL,
  rubric_version_id NUMBER NOT NULL REFERENCES rubric_version(id),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_fluke_review UNIQUE (review_id)
);
