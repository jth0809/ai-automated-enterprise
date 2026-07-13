-- V7__tracker_historical_sources.sql
--
-- Register the approved reference-only source catalog. These rows are metadata
-- for offline historical evidence resolution; this migration creates no feed
-- URL and no source_domain egress policy for the four historical-only sources.
-- NASA already exists from V2 and therefore remains an active configured feed.

INSERT INTO source_registry
  (code, name, source_type, tier, rss_url, site_domain, feed_active)
SELECT
  'NASA',
  'National Aeronautics and Space Administration',
  'AGENCY',
  1,
  NULL,
  'nasa.gov',
  'Y'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM source_registry WHERE code = 'NASA'
);

-- V2 already owns NASA's feed URL and active policy. Align only the catalog
-- identity fields; do not change its feed or source_domain rows.
UPDATE source_registry
   SET name = 'National Aeronautics and Space Administration',
       source_type = 'AGENCY',
       tier = 1,
       site_domain = 'nasa.gov'
 WHERE code = 'NASA';

INSERT INTO source_registry
  (code, name, source_type, tier, rss_url, site_domain, feed_active)
SELECT
  'FAA',
  'Federal Aviation Administration',
  'AGENCY',
  1,
  NULL,
  'faa.gov',
  'N'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM source_registry WHERE code = 'FAA'
);

INSERT INTO source_registry
  (code, name, source_type, tier, rss_url, site_domain, feed_active)
SELECT
  'UNOOSA',
  'United Nations Office for Outer Space Affairs',
  'AGENCY',
  1,
  NULL,
  'unoosa.org',
  'N'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM source_registry WHERE code = 'UNOOSA'
);

INSERT INTO source_registry
  (code, name, source_type, tier, rss_url, site_domain, feed_active)
SELECT
  'GOVINFO',
  'United States Government Publishing Office GovInfo',
  'AGENCY',
  1,
  NULL,
  'govinfo.gov',
  'N'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM source_registry WHERE code = 'GOVINFO'
);

INSERT INTO source_registry
  (code, name, source_type, tier, rss_url, site_domain, feed_active)
SELECT
  'LSA',
  'Luxembourg Space Agency',
  'AGENCY',
  1,
  NULL,
  'public.lu',
  'N'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM source_registry WHERE code = 'LSA'
);
