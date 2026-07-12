-- Phase 1b twelve-feed registry and exact feed/body host policy.
-- URLs and redirect targets were validated against the public endpoints on
-- 2026-07-12. Feed activation remains inert unless TRACKER_FEEDS config lists
-- the corresponding entries and the Cilium policy admits the host.

UPDATE source_registry
   SET rss_url = 'https://www.nasa.gov/news-release/feed/',
       site_domain = 'www.nasa.gov'
 WHERE code = 'NASA';

UPDATE source_registry
   SET rss_url = 'https://www.esa.int/rssfeed/Our_Activities',
       site_domain = 'www.esa.int'
 WHERE code = 'ESA';

UPDATE source_registry
   SET rss_url = 'https://global.jaxa.jp/rss/press.rdf',
       site_domain = 'global.jaxa.jp'
 WHERE code = 'JAXA';

UPDATE source_registry
   SET rss_url = 'https://rss.arxiv.org/rss/astro-ph.EP',
       site_domain = 'arxiv.org'
 WHERE code = 'ARXIV';

UPDATE source_registry
   SET rss_url = 'https://spacenews.com/feed/',
       site_domain = 'spacenews.com'
 WHERE code = 'SPACENEWS';

UPDATE source_registry
   SET rss_url = 'https://www.nasaspaceflight.com/feed/',
       site_domain = 'www.nasaspaceflight.com'
 WHERE code = 'NASASPACEFLIGHT';

UPDATE source_registry
   SET rss_url = 'https://spaceflightnow.com/feed/',
       site_domain = 'spaceflightnow.com'
 WHERE code = 'SPACEFLIGHT_NOW';

UPDATE source_registry
   SET rss_url = 'https://www.planetary.org/rss/articles',
       site_domain = 'www.planetary.org'
 WHERE code = 'PLANETARY_SOCIETY';

UPDATE source_registry
   SET rss_url = 'https://phys.org/rss-feed/space-news/',
       site_domain = 'phys.org'
 WHERE code = 'PHYSORG_SPACE';

UPDATE source_registry
   SET rss_url = 'https://www.space.com/feeds.xml',
       site_domain = 'www.space.com'
 WHERE code = 'SPACE_COM';

UPDATE source_registry
   SET name = 'Ars Technica - Science',
       rss_url = 'https://feeds.arstechnica.com/arstechnica/science',
       site_domain = 'arstechnica.com'
 WHERE code = 'ARSTECHNICA_SPACE';

UPDATE source_registry
   SET rss_url = 'https://www.universetoday.com/rss.xml',
       site_domain = 'www.universetoday.com'
 WHERE code = 'UNIVERSE_TODAY';

UPDATE source_registry
   SET feed_active = CASE
         WHEN code IN (
           'NASA','ESA','JAXA','ARXIV','SPACENEWS','NASASPACEFLIGHT',
           'SPACEFLIGHT_NOW','PLANETARY_SOCIETY','PHYSORG_SPACE','SPACE_COM',
           'ARSTECHNICA_SPACE','UNIVERSE_TODAY'
         ) THEN 'Y'
         ELSE 'N'
       END;

-- Existing V3 representative rows become active policy entries. arXiv and
-- Ars use different feed and body hosts, so their original rows are FEED-only.
UPDATE source_domain
   SET active = 'Y', purpose = 'BOTH'
 WHERE source_id IN (
       SELECT id FROM source_registry
        WHERE code IN (
          'NASA','ESA','JAXA','SPACENEWS','NASASPACEFLIGHT','SPACEFLIGHT_NOW',
          'PLANETARY_SOCIETY','PHYSORG_SPACE','SPACE_COM','UNIVERSE_TODAY'
        )
      );

UPDATE source_domain
   SET active = 'Y', purpose = 'FEED'
 WHERE source_id = (SELECT id FROM source_registry WHERE code = 'ARXIV')
   AND domain = 'rss.arxiv.org';

UPDATE source_domain
   SET active = 'Y', purpose = 'FEED'
 WHERE source_id = (SELECT id FROM source_registry WHERE code = 'ARSTECHNICA_SPACE')
   AND domain = 'feeds.arstechnica.com';

INSERT INTO source_domain (source_id, domain, purpose, active)
SELECT id, 'science.nasa.gov', 'BODY', 'Y'
  FROM source_registry s
 WHERE code = 'NASA'
   AND NOT EXISTS (
       SELECT 1 FROM source_domain d
        WHERE d.source_id = s.id AND d.domain = 'science.nasa.gov'
   );

INSERT INTO source_domain (source_id, domain, purpose, active)
SELECT id, 'arxiv.org', 'BODY', 'Y'
  FROM source_registry s
 WHERE code = 'ARXIV'
   AND NOT EXISTS (
       SELECT 1 FROM source_domain d
        WHERE d.source_id = s.id AND d.domain = 'arxiv.org'
   );

INSERT INTO source_domain (source_id, domain, purpose, active)
SELECT id, 'export.arxiv.org', 'BODY', 'Y'
  FROM source_registry s
 WHERE code = 'ARXIV'
   AND NOT EXISTS (
       SELECT 1 FROM source_domain d
        WHERE d.source_id = s.id AND d.domain = 'export.arxiv.org'
   );

INSERT INTO source_domain (source_id, domain, purpose, active)
SELECT id, 'arstechnica.com', 'BODY', 'Y'
  FROM source_registry s
 WHERE code = 'ARSTECHNICA_SPACE'
   AND NOT EXISTS (
       SELECT 1 FROM source_domain d
        WHERE d.source_id = s.id AND d.domain = 'arstechnica.com'
   );
