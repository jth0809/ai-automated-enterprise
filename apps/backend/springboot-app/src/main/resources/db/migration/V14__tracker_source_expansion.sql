-- Phase 3 WP3.5: bounded non-RSS source discovery and reviewed governance facts.
-- HTML-index discoveries are quarantined from the evaluation pipeline until a
-- later, explicit LIVE_MODEL promotion. Governance rows contain authored fact
-- summaries and provenance only; no source body or binary content is stored.

ALTER TABLE article ADD evaluation_allowed CHAR(1) DEFAULT 'Y' NOT NULL;
ALTER TABLE article ADD CONSTRAINT ck_article_evaluation_allowed
  CHECK (evaluation_allowed IN ('Y','N'));

CREATE TABLE governance_record (
  id                     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  record_id              VARCHAR2(100) NOT NULL UNIQUE,
  record_type            VARCHAR2(30) NOT NULL,
  jurisdiction           VARCHAR2(100) NOT NULL,
  subject                VARCHAR2(500) NOT NULL,
  record_status          VARCHAR2(80) NOT NULL,
  effective_on           DATE NOT NULL,
  effective_on_precision VARCHAR2(10) NOT NULL,
  source_id              NUMBER NOT NULL REFERENCES source_registry(id),
  source_url             VARCHAR2(1000) NOT NULL,
  accessed_on            DATE NOT NULL,
  content_sha256         CHAR(64) NOT NULL,
  publication_path       VARCHAR2(20) NOT NULL,
  fact_summary           VARCHAR2(1000) NOT NULL,
  review_status          VARCHAR2(20) NOT NULL,
  dataset_version        VARCHAR2(80) NOT NULL,
  created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_governance_record_type CHECK (record_type IN
    ('TREATY_STATUS','TREATY_ACTION','LICENSE_FRAMEWORK','REGULATORY_NOTICE')),
  CONSTRAINT ck_governance_precision CHECK (effective_on_precision IN
    ('DAY','MONTH','YEAR')),
  CONSTRAINT ck_governance_publication_path CHECK (publication_path = 'PRIMARY'),
  CONSTRAINT ck_governance_review_status CHECK (review_status = 'HUMAN_REVIEWED')
);

CREATE INDEX ix_governance_effective
  ON governance_record(effective_on, record_id);

CREATE TABLE governance_import (
  dataset_version VARCHAR2(80) PRIMARY KEY,
  dataset_sha256  CHAR(64) NOT NULL UNIQUE,
  record_count    NUMBER(3) NOT NULL,
  loaded_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_governance_import_count CHECK (record_count BETWEEN 1 AND 100)
);

INSERT INTO source_registry
  (code, name, source_type, tier, rss_url, site_domain, feed_active)
SELECT 'ISRO', 'Indian Space Research Organisation', 'AGENCY', 1,
       NULL, 'www.isro.gov.in', 'N'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE code = 'ISRO');

INSERT INTO source_registry
  (code, name, source_type, tier, rss_url, site_domain, feed_active)
SELECT 'CNSA', 'China National Space Administration - Official Notices',
       'AGENCY', 1, NULL, 'www.cnsa.gov.cn', 'N'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE code = 'CNSA');

INSERT INTO source_registry
  (code, name, source_type, tier, rss_url, site_domain, feed_active)
SELECT 'CNSA_HOSTED_MEDIA', 'CNSA English Hosted News', 'GENERAL_MEDIA', 3,
       NULL, 'www.cnsa.gov.cn', 'N'
  FROM DUAL
 WHERE NOT EXISTS (
       SELECT 1 FROM source_registry WHERE code = 'CNSA_HOSTED_MEDIA');

INSERT INTO source_domain (source_id, domain, purpose, active)
SELECT id, 'www.isro.gov.in', 'BOTH', 'Y'
  FROM source_registry s
 WHERE code = 'ISRO'
   AND NOT EXISTS (
       SELECT 1 FROM source_domain d
        WHERE d.source_id = s.id AND d.domain = 'www.isro.gov.in');

INSERT INTO source_domain (source_id, domain, purpose, active)
SELECT id, 'www.cnsa.gov.cn', 'BOTH', 'Y'
  FROM source_registry s
 WHERE code = 'CNSA'
   AND NOT EXISTS (
       SELECT 1 FROM source_domain d
        WHERE d.source_id = s.id AND d.domain = 'www.cnsa.gov.cn');

INSERT INTO source_domain (source_id, domain, purpose, active)
SELECT id, 'www.cnsa.gov.cn', 'BOTH', 'Y'
  FROM source_registry s
 WHERE code = 'CNSA_HOSTED_MEDIA'
   AND NOT EXISTS (
       SELECT 1 FROM source_domain d
        WHERE d.source_id = s.id AND d.domain = 'www.cnsa.gov.cn');

COMMENT ON COLUMN article.evaluation_allowed IS
  'N quarantines discovery metadata from gate/classify until explicit reviewed promotion';
COMMENT ON COLUMN governance_record.fact_summary IS
  'Reviewer-authored fact only; no source body, quote, HTML, PDF, image, or binary';
