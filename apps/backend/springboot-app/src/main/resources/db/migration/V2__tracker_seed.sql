-- V2__tracker_seed.sql
--
-- Seed data for the Multiplanetary Tracker Phase-1 baseline:
--   * capability_node — node set v0.1 (20 nodes), docs/plans/wp/tracker-rubric-v1.md §1
--   * source_registry — 12 feed sources + 4 registry-only entries, docs/plans/wp/tracker-infra-prework.md §1
--   * parameter_set   — 'params-v1' (all DDL-doc default values, active)
--   * rubric_version  — 'r1.0' (prompt hashes are 64-zero placeholders until Task 10/11 compute real ones)
--
-- shedlock is intentionally left empty here — ShedLock populates/updates
-- lock rows itself at runtime; there is no seed row for it.

-- ---------------------------------------------------------------------------
-- capability_node (20 rows, node_set_version 'nodes-v0.1', current_level=0)
-- Weight sums per pillar (verified == 1.0000): P1 .25+.25+.15+.20+.15=1.00,
-- P2 .35+.25+.20+.20=1.00, P3 .40+.35+.25=1.00, P4 .45+.35+.20=1.00,
-- P5 .50+.50=1.00, P6 .40+.35+.25=1.00
-- ---------------------------------------------------------------------------
INSERT INTO capability_node (code, pillar, name_ko, scale_type, current_level, weight, node_set_version) VALUES
  ('P1-REUSE-LV',        1, '완전 재사용 발사체',                         'TRL', 0, 0.25, 'nodes-v0.1'),
  ('P1-ORBIT-REFUEL',    1, '궤도 추진제 이송·급유',                      'TRL', 0, 0.25, 'nodes-v0.1'),
  ('P1-DEEP-PROP',       1, '심우주 추진 (NTP/전기추진)',                 'TRL', 0, 0.15, 'nodes-v0.1'),
  ('P1-EDL-HEAVY',       1, '대형(10t+) 화물 행성 진입·강하·착륙',        'TRL', 0, 0.20, 'nodes-v0.1'),
  ('P1-SURFACE-ASCENT',  1, '행성 표면 이륙·귀환',                        'TRL', 0, 0.15, 'nodes-v0.1'),
  ('P2-ECLSS',           2, '폐쇄 순환 생명 유지 (물·공기)',              'TRL', 0, 0.35, 'nodes-v0.1'),
  ('P2-FOOD',            2, '우주·행성 표면 식량 생산',                   'TRL', 0, 0.25, 'nodes-v0.1'),
  ('P2-RAD',             2, '방사선 방호 (심우주·표면)',                  'TRL', 0, 0.20, 'nodes-v0.1'),
  ('P2-MED',             2, '저중력 장기 체류 의학',                      'TRL', 0, 0.20, 'nodes-v0.1'),
  ('P3-CONSTRUCT',       3, '표면 거주지 건설 (현지 재료 포함)',          'TRL', 0, 0.40, 'nodes-v0.1'),
  ('P3-POWER',           3, '표면 전력 계통 (발전·저장·배전)',            'TRL', 0, 0.35, 'nodes-v0.1'),
  ('P3-COMMS',           3, '행성 간·표면 통신망',                        'TRL', 0, 0.25, 'nodes-v0.1'),
  ('P4-ISRU-PROP',       4, 'ISRU: 추진제·물·산소 현지 생산',             'TRL', 0, 0.45, 'nodes-v0.1'),
  ('P4-NUKE',            4, '우주용 소형 원자로/핵융합',                  'TRL', 0, 0.35, 'nodes-v0.1'),
  ('P4-MATERIALS',       4, '극한환경 구조 소재',                         'TRL', 0, 0.20, 'nodes-v0.1'),
  ('P5-AUTOCON',         5, '무인 선행 건설·조립 로봇',                   'TRL', 0, 0.50, 'nodes-v0.1'),
  ('P5-AUTONOMY',        5, '장주기 자율 운영 (항법·정비·이상 대응)',     'TRL', 0, 0.50, 'nodes-v0.1'),
  ('P6-LAUNCH-MARKET',   6, '발사 시장·수송 경제성',                      'EGL', 0, 0.40, 'nodes-v0.1'),
  ('P6-GOV-FRAMEWORK',   6, '우주 자원·거주 국제 규범/법제',              'EGL', 0, 0.35, 'nodes-v0.1'),
  ('P6-FUNDING',         6, '지속 가능 자금 조달·민간 투자',              'EGL', 0, 0.25, 'nodes-v0.1');

-- ---------------------------------------------------------------------------
-- source_registry (16 rows = 12 feed sources + 4 registry-only entries)
-- feed_active='Y' only for NASA, ESA, SpaceNews, NASASpaceflight (task
-- controller resolution #6); all other feed sources and all registry-only
-- entries (rss_url NULL = not a feed, per the DDL doc's own column comment)
-- are feed_active='N'.
-- ---------------------------------------------------------------------------
INSERT INTO source_registry (code, name, source_type, tier, rss_url, site_domain, feed_active) VALUES
  ('NASA',               'NASA Breaking News',                    'AGENCY',            1, 'https://www.nasa.gov/rss/dyn/breaking_news.rss',   'www.nasa.gov',            'Y'),
  ('ESA',                'ESA Space News',                        'AGENCY',            1, 'https://www.esa.int/rssfeed/Our_Activities',        'www.esa.int',             'Y'),
  ('JAXA',               'JAXA Press Releases',                   'AGENCY',            1, 'https://global.jaxa.jp/press/rss/press_e.rdf',      'global.jaxa.jp',          'N'),
  ('ARXIV',              'arXiv (astro-ph.EP, physics.space-ph)', 'PREPRINT',          2, 'https://rss.arxiv.org/rss/astro-ph.EP',             'rss.arxiv.org',           'N'),
  ('SPACENEWS',          'SpaceNews',                             'SPECIALIZED_MEDIA', 2, 'https://spacenews.com/feed/',                       'spacenews.com',           'Y'),
  ('NASASPACEFLIGHT',    'NASASpaceflight',                       'SPECIALIZED_MEDIA', 2, 'https://www.nasaspaceflight.com/feed/',             'www.nasaspaceflight.com', 'Y'),
  ('SPACEFLIGHT_NOW',    'Spaceflight Now',                       'SPECIALIZED_MEDIA', 2, 'https://spaceflightnow.com/feed/',                  'spaceflightnow.com',      'N'),
  ('PLANETARY_SOCIETY',  'The Planetary Society',                 'SPECIALIZED_MEDIA', 2, 'https://www.planetary.org/rss/articles',            'www.planetary.org',       'N'),
  ('PHYSORG_SPACE',      'Phys.org – Space',                      'GENERAL_MEDIA',     3, 'https://phys.org/rss-feed/space-news/',             'phys.org',                'N'),
  ('SPACE_COM',          'Space.com',                             'GENERAL_MEDIA',     3, 'https://www.space.com/feeds/all',                   'www.space.com',           'N'),
  ('ARSTECHNICA_SPACE',  'Ars Technica – Space',                  'GENERAL_MEDIA',     3, 'https://feeds.arstechnica.com/arstechnica/space',   'feeds.arstechnica.com',   'N'),
  ('UNIVERSE_TODAY',     'Universe Today',                        'GENERAL_MEDIA',     3, 'https://www.universetoday.com/feed/',               'www.universetoday.com',  'N'),
  ('SPACEX',             'SpaceX',                                'CORPORATE',         3, NULL,                                                 'www.spacex.com',         'N'),
  ('BLUE_ORIGIN',        'Blue Origin',                           'CORPORATE',         3, NULL,                                                 'www.blueorigin.com',     'N'),
  ('NATURE',             'Nature',                                'JOURNAL',           1, NULL,                                                 'www.nature.com',         'N'),
  ('SCIENCE',            'Science',                               'JOURNAL',           1, NULL,                                                 'www.science.org',        'N');

-- ---------------------------------------------------------------------------
-- parameter_set 'params-v1' — all values equal the DDL-doc column defaults,
-- listed explicitly for auditability. trl_map/maturity_map per task
-- controller resolution #3 (identical curve at seed time).
-- ---------------------------------------------------------------------------
INSERT INTO parameter_set (
  version_label, active, epsilon, k_shrink, window_m, window_fixed_years, window_min_years, window_max_years,
  dormancy_start, dormancy_step_per_decade, dormancy_floor, dormancy_trigger_years, default_delta_e,
  eta_clamp_min_years, eta_clamp_max_years, display_damping_days_per_day, daily_cost_cap_usd,
  trl_map, maturity_map
) VALUES (
  'params-v1', 'Y', 0.010, 4, 6, 10, 4, 15,
  0.85, 0.15, 0.40, 15, 0.150,
  2, 150, 90, 20.00,
  '{"1":0.03,"2":0.07,"3":0.12,"4":0.20,"5":0.30,"6":0.45,"7":0.65,"8":0.85,"9":1.0}',
  '{"1":0.03,"2":0.07,"3":0.12,"4":0.20,"5":0.30,"6":0.45,"7":0.65,"8":0.85,"9":1.0}'
);

-- ---------------------------------------------------------------------------
-- rubric_version 'r1.0' — model IDs per task controller resolution #4.
-- Prompt hashes = SHA-256 of the LF-normalized classpath files
-- tracker/prompt-gate.txt and tracker/prompt-classify-system.txt.
-- ---------------------------------------------------------------------------
INSERT INTO rubric_version (
  version_label, gate_model, classify_model, gate_prompt_sha256, classify_prompt_sha256,
  node_set_version, active
) VALUES (
  'r1.0', 'claude-haiku-4-5-20251001', 'claude-opus-4-8',
  '2762bf328c8bc4d87b47bf590ce4602b14ad4d11f611964d872ec78440077963',
  '664e6aa3ee3588b5f3b7a2af702a6cc3be5b81f19574be0402fe1f6256498a93',
  'nodes-v0.1', 'Y'
);
