-- Phase 1b full-text extraction state and application-level egress policy.
-- Keep this migration byte-for-byte compatible with Oracle ATP and H2 in
-- MODE=Oracle; production egress still requires a matching Cilium policy.

CREATE TABLE source_domain (
  id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_id NUMBER NOT NULL REFERENCES source_registry(id),
  domain    VARCHAR2(253) NOT NULL,
  purpose   VARCHAR2(5) NOT NULL CHECK (purpose IN ('FEED','BODY','BOTH')),
  active    CHAR(1) DEFAULT 'Y' NOT NULL CHECK (active IN ('Y','N')),
  CONSTRAINT uq_source_domain UNIQUE (source_id, domain)
);

ALTER TABLE article ADD body_extraction_status VARCHAR2(10) DEFAULT 'SKIPPED' NOT NULL;
ALTER TABLE article ADD body_extraction_attempts NUMBER(2) DEFAULT 0 NOT NULL;
ALTER TABLE article ADD body_extraction_error VARCHAR2(1000);
ALTER TABLE article ADD CONSTRAINT ck_article_body_status
  CHECK (body_extraction_status IN ('PENDING','EXTRACTED','SKIPPED','FAILED'));
ALTER TABLE article ADD CONSTRAINT ck_article_body_attempts
  CHECK (body_extraction_attempts BETWEEN 0 AND 99);

UPDATE article
   SET body_extraction_status = CASE
         WHEN body_extracted = 'Y' THEN 'EXTRACTED'
         ELSE 'SKIPPED'
       END;

INSERT INTO source_domain (source_id, domain, purpose, active)
SELECT id,
       LOWER(site_domain),
       CASE WHEN rss_url IS NULL THEN 'BODY' ELSE 'BOTH' END,
       feed_active
  FROM source_registry;
