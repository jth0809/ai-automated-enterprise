# Tracker Phase 1b Core Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Phase 1b's Layer C core with secure full-text collection from twelve feeds, an independently prompted fluke filter, and a usable evidence-first human review UI while leaving the unproven G1a/G1 operational gates open.

**Architecture:** Extend the existing single Spring Boot deployment with two bounded ShedLock jobs: `BodyExtractionJob` upgrades RSS summaries to full text before the relevance gate, and `FlukeFilterJob` evaluates only review-triggering events without ever advancing state automatically. Persist egress policy and audit data in Oracle-compatible Flyway migrations, retain the existing natural-key merger, and extend the existing React tracker tab with a memory-only-token review panel.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway/Oracle ATP with H2 Oracle-mode tests, ShedLock 5.14, jsoup 1.22.2, React 19, TypeScript, Vitest, CiliumNetworkPolicy, FluxCD, OCI Vault/ESO.

## Global Constraints

- Work only in `.claude/worktrees/tracker-mvp` on `feat/tracker-mvp`; preserve the user's unrelated untracked `.claude/`, `application-demo.yml`, and `backfill-demo.json` files and never include them in task commits.
- Execute inline without subagents, per the user's earlier execution-mode decision.
- Use TDD for every behavior change: demonstrate RED, implement the smallest complete behavior, demonstrate focused GREEN, then run the relevant regression suite before committing.
- No automated test may call a live RSS host or LLM API. HTTP is exercised through deterministic fake transports; Anthropic is mocked.
- No site-specific parser, hostname branch, or CSS selector is allowed in extraction code.
- No new pod, queue, broker, or Kubernetes CronJob. Jobs stay inside the existing resource-capped backend deployment.
- Every new outbound hostname must exist in both database policy and the backend CiliumNetworkPolicy before it can be enabled.
- Never commit `TRACKER_FEEDS`, `TRACKER_ADMIN_TOKEN`, or API keys as values. OCI Vault and ESO remain the only production secret path.
- LLM output remains enum plus exact evidence quote; verification, score, and state transitions remain code-derived and idempotent.
- Embedding generation and semantic event merge remain Phase 2 work under the specific WP1.5 contract.
- Starting verified baselines: backend 127 tests and frontend 34 tests, both with zero failures.
- Backend verification command in the current Codex environment:

```powershell
$Maven = 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\8e74001100ff70d6af083c5511fcc5ec49282d7017cde82c3698eee8fdf86698\bin\mvn.cmd'
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
```

- If a newly introduced dependency is not cached, rerun Maven without `-o` only after obtaining network approval; do not substitute an older cached library merely to keep offline mode green.

---

### Task 1: Full-text extraction schema and policy table

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V3__tracker_body_extraction.sql`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerPhase1bSchemaTest.java`
- Modify: `docs/plans/wp/tracker-data-model.md`

**Interfaces:**
- Produces table `source_domain(source_id, domain, purpose, active)` with unique `(source_id, domain)`.
- Produces article columns `body_extraction_status`, `body_extraction_attempts`, and `body_extraction_error`.
- Existing rows map `body_extracted='Y' -> EXTRACTED`, otherwise `SKIPPED`.

- [ ] **Step 1: Write the failing schema tests**

Add tests that inspect metadata and constraints, then exercise the uniqueness rule:

```java
@Test
void phase1bBodyExtractionSchemaExists() {
    assertColumn("ARTICLE", "BODY_EXTRACTION_STATUS");
    assertColumn("ARTICLE", "BODY_EXTRACTION_ATTEMPTS");
    assertColumn("ARTICLE", "BODY_EXTRACTION_ERROR");
    assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='SOURCE_DOMAIN'")
            .query(Integer.class).single());
}

@Test
void oneSourceCannotDeclareTheSameDomainTwice() {
    long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code='NASA'").query(Long.class).single();
    jdbc.sql("INSERT INTO source_domain(source_id,domain,purpose,active) VALUES(:id,'example.test','BODY','Y')")
            .param("id", sourceId).update();
    assertThrows(DuplicateKeyException.class, () ->
            jdbc.sql("INSERT INTO source_domain(source_id,domain,purpose,active) VALUES(:id,'example.test','FEED','Y')")
                    .param("id", sourceId).update());
}
```

- [ ] **Step 2: Run the schema test and verify RED**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerPhase1bSchemaTest'
```

Expected: failure because migration V3, `source_domain`, and extraction columns do not exist.

- [ ] **Step 3: Implement the Oracle/H2-compatible migration**

Create V3 with explicit checks and deterministic backfill:

```sql
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

UPDATE article SET body_extraction_status =
  CASE WHEN body_extracted = 'Y' THEN 'EXTRACTED' ELSE 'SKIPPED' END;

INSERT INTO source_domain(source_id, domain, purpose, active)
SELECT id, LOWER(site_domain),
       CASE WHEN rss_url IS NULL THEN 'BODY' ELSE 'BOTH' END,
       feed_active
  FROM source_registry;
```

Update the data-model document with the new table, fields, constraints, and the reason one source may own multiple hosts.

- [ ] **Step 4: Run focused and schema regression tests**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerPhase1bSchemaTest,TrackerSchemaTest'
```

Expected: both classes pass; Flyway validates and applies V1-V3.

- [ ] **Step 5: Commit Task 1**

```powershell
git add apps/backend/springboot-app/src/main/resources/db/migration/V3__tracker_body_extraction.sql apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerPhase1bSchemaTest.java docs/plans/wp/tracker-data-model.md
git commit -m "feat(tracker): add full-text extraction schema"
```

---

### Task 2: Twelve-source policy, feed validation, and repository contracts

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V4__tracker_phase1b_sources.sql`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/SourceDomainRow.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/config/TrackerFeedPolicyTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSchemaTest.java`
- Modify: `docs/plans/wp/tracker-infra-prework.md`

**Interfaces:**
- `record SourceDomainRow(long sourceId, String sourceCode, String domain, String purpose)`.
- `List<SourceDomainRow> TrackerRepository.findActiveSourceDomains()` supplies the complete policy set for startup validation and manifest parity tests.
- `Set<String> TrackerRepository.findActiveDomains(long sourceId, String purpose)` returns exact lowercase hosts where `purpose` matches the requested purpose or `BOTH`.
- `boolean TrackerRepository.isRegisteredFeed(String sourceCode, String host)` validates runtime `TRACKER_FEEDS` entries.
- `TrackerConfig.trackerFeeds` rejects unknown source/host pairs during context startup.

- [ ] **Step 1: Validate current official feed endpoints outside automated tests**

For each of the twelve source codes, check the provider's official feed directory or the configured endpoint, record final HTTPS URL and redirect hosts in `tracker-infra-prework.md`, and reject any endpoint that returns non-feed HTML. The accepted list must contain exactly:

```text
NASA, ESA, JAXA, ARXIV, SPACENEWS, NASASPACEFLIGHT,
SPACEFLIGHT_NOW, PLANETARY_SOCIETY, PHYSORG_SPACE,
SPACE_COM, ARSTECHNICA_SPACE, UNIVERSE_TODAY
```

The check is evidence gathering only; do not turn it into a live CI test.

- [ ] **Step 2: Write failing policy and seed tests**

```java
@Test
void allTwelvePhase1bFeedsAreActiveAndPolicyBacked() {
    assertEquals(12, jdbc.sql("SELECT COUNT(*) FROM source_registry WHERE feed_active='Y'")
            .query(Integer.class).single());
    assertEquals(0, jdbc.sql("""
            SELECT COUNT(*) FROM source_registry s
             WHERE s.feed_active='Y'
               AND NOT EXISTS (SELECT 1 FROM source_domain d
                                WHERE d.source_id=s.id AND d.active='Y'
                                  AND d.purpose IN ('FEED','BOTH'))
            """).query(Integer.class).single());
}

@Test
void configuredFeedMustMatchItsRegisteredSourceAndHost() {
    contextRunner
            .withPropertyValues("tracker.enabled=true", "tracker.feeds=NASA|https://evil.test/rss")
            .run(context -> assertThat(context).hasFailed()
                    .getFailure().hasRootCauseInstanceOf(IllegalArgumentException.class));
}
```

- [ ] **Step 3: Run tests and verify RED**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerFeedPolicyTest,TrackerSchemaTest'
```

Expected: only four active feeds and no repository/config policy methods.

- [ ] **Step 4: Seed twelve sources and implement policy queries**

V4 updates validated `rss_url`, sets exactly the twelve feed rows active, and inserts additional feed/body hosts with idempotent `INSERT ... SELECT ... WHERE NOT EXISTS` statements. Implement exact-host matching:

```java
public Set<String> findActiveDomains(long sourceId, String purpose) {
    return Set.copyOf(jdbc.sql("""
            SELECT domain FROM source_domain
             WHERE source_id=:sourceId AND active='Y'
               AND purpose IN (:purpose,'BOTH')
            """)
            .param("sourceId", sourceId)
            .param("purpose", purpose)
            .query(String.class).list());
}

public boolean isRegisteredFeed(String sourceCode, String host) {
    return jdbc.sql("""
            SELECT COUNT(*) FROM source_registry s JOIN source_domain d ON d.source_id=s.id
             WHERE UPPER(s.code)=UPPER(:code) AND s.feed_active='Y' AND d.active='Y'
               AND d.purpose IN ('FEED','BOTH') AND LOWER(d.domain)=LOWER(:host)
            """)
            .param("code", sourceCode).param("host", host)
            .query(Integer.class).single() == 1;
}
```

`TrackerConfig` parses `TRACKER_FEEDS`, validates every URI as HTTPS with a nonblank host, and rejects any source/host pair not registered in the database. Empty configuration remains safe and yields no feeds.

- [ ] **Step 5: Run focused tests and backend regression**

Run focused tests, then full backend tests. Expected: twelve active feeds, no unknown feed accepted, all previous tests remain green.

- [ ] **Step 6: Commit Task 2**

```powershell
git add apps/backend/springboot-app/src/main/resources/db/migration/V4__tracker_phase1b_sources.sql apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/SourceDomainRow.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/config/TrackerFeedPolicyTest.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSchemaTest.java docs/plans/wp/tracker-infra-prework.md
git commit -m "feat(tracker): register twelve policy-backed feeds"
```

---

### Task 3: RSS 2.0, RDF, and Atom parser support

**Files:**
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/news/RssParser.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/news/RssParserTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/TrackerIngestJob.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/TrackerIngestJobTest.java`

**Interfaces:**
- Preserve `List<Article> RssParser.parse(String xml, String source)`.
- Parse RSS/RDF `item` and Atom `entry`; Atom uses `link rel="alternate"` and ISO-8601 `published`/`updated`.
- Skip malformed entries individually while malformed XML still fails the feed.

- [ ] **Step 1: Add failing RDF/Atom and malformed-entry tests**

```java
@Test
void parsesAtomAlternateLinkAndPublishedTimestamp() {
    String atom = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry><title>Transfer demo</title>
            <link rel="alternate" href="https://example.test/a"/>
            <published>2026-07-12T01:02:03Z</published>
            <summary>Propellant moved between tanks.</summary>
          </entry>
        </feed>
        """;
    Article article = parser.parse(atom, "TEST").getFirst();
    assertEquals("https://example.test/a", article.link());
    assertEquals("2026-07-12T01:02:03Z", article.publishedAt());
}
```

Add an RDF fixture and a feed containing one entry without a link between two valid entries; assert two results and no document-wide failure.

- [ ] **Step 2: Run parser tests and verify RED**

Expected: Atom produces no articles or a null link.

- [ ] **Step 3: Implement namespace-tolerant entry parsing**

Set `DocumentBuilderFactory.setNamespaceAware(true)`. Select elements by local name and parse links by attributes rather than text:

```java
private static String atomLink(Element entry) {
    for (Element link : childElements(entry, "link")) {
        String rel = link.getAttribute("rel");
        if ((rel.isBlank() || "alternate".equals(rel)) && !link.getAttribute("href").isBlank()) {
            return link.getAttribute("href").trim();
        }
    }
    return null;
}
```

Keep all existing XXE/DTD protections. Update `TrackerIngestJob.parsePublishedAt` to try RFC 1123 and `Instant.parse`, and catch errors per article inside `ingestFeed`.

- [ ] **Step 4: Run focused and news/tracker ingest regression tests**

Expected: RSS, RDF, Atom, entry isolation, feed isolation, and duplicate insertion tests pass.

- [ ] **Step 5: Commit Task 3**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/news/RssParser.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/news/RssParserTest.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/TrackerIngestJob.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/TrackerIngestJobTest.java
git commit -m "feat(tracker): parse RSS RDF and Atom feeds"
```

---

### Task 4: Bounded allowlisted article page fetcher

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/ArticlePageFetcher.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/PageTransport.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/JdkPageTransport.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/FetchedPage.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/ArticlePageFetcherTest.java`

**Interfaces:**
- `FetchedPage ArticlePageFetcher.fetch(URI uri, Set<String> allowedHosts)`.
- `PageTransport.Response PageTransport.get(URI uri)` returns status, headers, and an `InputStream`; production transport disables automatic redirects.
- Maximum response 2 MiB, at most three redirects, exact HTTPS host policy on every hop.

- [ ] **Step 1: Write failing policy/redirect/size tests with a fake transport**

```java
@Test
void rejectsRedirectOutsideAllowlist() {
    transport.respond("https://allowed.test/a", 302, Map.of("location", List.of("https://evil.test/b")), "");
    assertThrows(SecurityException.class,
            () -> fetcher.fetch(URI.create("https://allowed.test/a"), Set.of("allowed.test")));
}

@Test
void rejectsBodiesOverTwoMiB() {
    transport.respond("https://allowed.test/a", 200,
            Map.of("content-type", List.of("text/html")), "x".repeat(2 * 1024 * 1024 + 1));
    assertThrows(IllegalArgumentException.class,
            () -> fetcher.fetch(URI.create("https://allowed.test/a"), Set.of("allowed.test")));
}
```

Also cover HTTP, user info, IP literals, explicit non-443 ports, unknown hosts, fourth redirect, missing/unsupported Content-Type, and a valid relative redirect.

- [ ] **Step 2: Run the focused test and verify RED**

Expected: classes do not exist.

- [ ] **Step 3: Implement pure URI policy and manual redirect loop**

```java
for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
    URI checked = validate(current, allowedHosts);
    try (PageTransport.Response response = transport.get(checked)) {
        if (isRedirect(response.status())) {
            if (hop == MAX_REDIRECTS) throw new IllegalStateException("redirect limit exceeded");
            current = checked.resolve(requiredLocation(response.headers()));
            continue;
        }
        requireSuccessAndHtml(response);
        return readBounded(response, checked, MAX_BYTES);
    }
}
```

Normalize hosts with `IDN.toASCII(...).toLowerCase(Locale.ROOT)`. Reject IP literals by both URI syntax and `InetAddress` parsing only for literal input; do not perform application-side DNS and introduce a TOCTOU check. Reuse one cookie-free `HttpClient` with `Redirect.NEVER`, 10-second connect timeout, and 15-second request timeout.

- [ ] **Step 4: Run focused tests**

Expected: all security and bounds cases pass without network access.

- [ ] **Step 5: Commit Task 4**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/ArticlePageFetcher.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/PageTransport.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/JdkPageTransport.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/FetchedPage.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/ArticlePageFetcherTest.java
git commit -m "feat(tracker): add bounded allowlisted page fetcher"
```

---

### Task 5: Generic jsoup Readability-style extractor

**Files:**
- Modify: `apps/backend/springboot-app/pom.xml`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/ArticleBodyExtractor.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/ExtractedArticle.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/JsoupReadabilityExtractor.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/JsoupReadabilityExtractorTest.java`
- Create fixtures: `apps/backend/springboot-app/src/test/resources/tracker/extraction/semantic-article.html`, `nested-divs.html`, `sidebar-heavy.html`, `malformed.html`

**Interfaces:**
- `ExtractedArticle ArticleBodyExtractor.extract(FetchedPage page)`.
- Output contains normalized title and paragraph-preserving text only.
- Fewer than 500 non-whitespace characters is an extraction failure; output caps at 200,000 characters.

- [ ] **Step 1: Add jsoup 1.22.2 and failing fixture tests**

```xml
<dependency>
  <groupId>org.jsoup</groupId>
  <artifactId>jsoup</artifactId>
  <version>1.22.2</version>
</dependency>
```

```java
@ParameterizedTest
@ValueSource(strings = {"semantic-article.html", "nested-divs.html", "sidebar-heavy.html", "malformed.html"})
void extractsEvidenceAndDropsBoilerplate(String fixture) {
    ExtractedArticle result = extractor.extract(page(fixture));
    assertTrue(result.text().contains("The transfer moved 1,200 kilograms of propellant."));
    assertFalse(result.text().contains("Subscribe to our newsletter"));
    assertFalse(result.text().contains("Related stories"));
}
```

- [ ] **Step 2: Run tests and verify RED**

Expected: missing extractor classes; if jsoup is absent from the local cache, obtain network approval and fetch exactly 1.22.2.

- [ ] **Step 3: Implement the generic density scorer**

Parse bytes with the final URI as base, remove non-content tags and hidden nodes, then score `article`, `main`, `section`, and `div` candidates:

```java
double score(Element e) {
    String own = normalizedParagraphText(e);
    int paragraphChars = e.select("p").stream().mapToInt(p -> p.text().length()).sum();
    int punctuation = countSentencePunctuation(own);
    double linkDensity = linkedTextLength(e) / (double) Math.max(1, e.text().length());
    return paragraphChars + e.select("p").size() * 80.0 + punctuation * 12.0
            - linkDensity * paragraphChars * 2.0 - boilerplatePenalty(e);
}
```

Join the best candidate and adjacent text-bearing siblings, preserve blank lines between paragraphs, and normalize internal whitespace. Boilerplate penalties are generic vocabulary only; no source-specific values are permitted.

- [ ] **Step 4: Run focused tests and full backend regression**

Expected: all fixtures preserve exact evidence sentences, no boilerplate assertions fail, and the previous 127-test baseline remains green with the new tests added.

- [ ] **Step 5: Commit Task 5**

```powershell
git add apps/backend/springboot-app/pom.xml apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/JsoupReadabilityExtractorTest.java apps/backend/springboot-app/src/test/resources/tracker/extraction
git commit -m "feat(tracker): extract generic full article text"
```

---

### Task 6: Body extraction lifecycle and gate ordering

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/ExtractionCandidate.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/BodyExtractionJob.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BodyExtractionJobTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/ArticleRow.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/TrackerIngestJob.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/RelevanceGate.java`
- Modify tests: `TrackerRepositoryTest.java`, `TrackerIngestJobTest.java`, `RelevanceGateTest.java`

**Interfaces:**
- `List<ExtractionCandidate> findPendingExtractions(int limit)` oldest-first.
- `completeExtraction(id, text)` atomically marks `EXTRACTED` and `body_extracted='Y'`.
- `recordExtractionFailure(id, message, maxAttempts)` returns the resulting status.
- `findByStatus("INGESTED", limit)` excludes `PENDING` extraction rows.

- [ ] **Step 1: Write failing state-transition and gate-race tests**

```java
@Test
void successfulExtractionMakesArticleVisibleToGate() {
    long articleId = insertPendingArticle();
    when(fetcher.fetch(any(), anySet())).thenReturn(pageWithEvidence());
    when(extractor.extract(any())).thenReturn(new ExtractedArticle("Title", FULL_TEXT));

    job.processPending();

    assertEquals("EXTRACTED", extractionStatus(articleId));
    assertEquals(FULL_TEXT, body(articleId));
    assertEquals(List.of(articleId), repository.findByStatus("INGESTED", 10).stream().map(ArticleRow::id).toList());
}

@Test
void pendingArticleIsInvisibleToRelevanceGate() {
    insertPendingArticle();
    assertTrue(repository.findByStatus("INGESTED", 10).isEmpty());
}
```

Also cover unknown-host `SKIPPED`, first/second retry staying pending, third failure becoming terminal `FAILED` with RSS body retained, and one article failure not blocking another.

- [ ] **Step 2: Run focused tests and verify RED**

Expected: extraction state methods and job are missing; pending rows currently appear in the gate query.

- [ ] **Step 3: Implement repository transitions and scheduled job**

`TrackerIngestJob` inserts the RSS body with `PENDING` only when the article host is an active BODY/BOTH domain for that source; otherwise it inserts `SKIPPED`. The job is bounded and split for ShedLock-safe tests:

```java
@Scheduled(cron = "${tracker.extract-cron:0 15 * * * *}")
@SchedulerLock(name = "tracker-body-extraction", lockAtLeastFor = "PT1M")
public void runOnce() { processPending(); }

public void processPending() {
    for (ExtractionCandidate candidate : repository.findPendingExtractions(30)) {
        try {
            FetchedPage page = fetcher.fetch(URI.create(candidate.url()), candidate.allowedHosts());
            repository.completeExtraction(candidate.id(), extractor.extract(page).text());
        } catch (RuntimeException error) {
            repository.recordExtractionFailure(candidate.id(), safeMessage(error), 3);
        }
    }
}
```

`findByStatus` adds `AND body_extraction_status <> 'PENDING'` only for `INGESTED`, preserving all other status queries.

- [ ] **Step 4: Run focused tests and full backend regression**

Expected: extraction ordering, fallback, article isolation, gate, and all existing pipeline tests pass.

- [ ] **Step 5: Commit Task 6**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/RelevanceGate.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker
git commit -m "feat(tracker): stage full-text extraction before gate"
```

---

### Task 7: Collection GitOps egress and safe defaults

**Files:**
- Modify: `gitops/apps/backend-springboot/network-policy.yaml`
- Modify: `gitops/apps/backend-springboot/deployment.yaml`
- Modify: `docs/plans/wp/tracker-infra-prework.md`
- Create: `gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1`

**Interfaces:**
- CNP host set equals the active `source_domain` deployment set.
- Deployment adds only non-secret extractor scheduling/bounds configuration; tracker remains disabled in PR-1 state.

- [ ] **Step 1: Write the failing manifest verifier**

The PowerShell verifier parses text deterministically and asserts every expected exact hostname appears once, port 443 is the only external feed/body port, `TRACKER_ENABLED` remains `false`, and no feed URL/token value exists in the manifest:

```powershell
$expected = @('www.nasa.gov','www.esa.int','global.jaxa.jp','rss.arxiv.org','arxiv.org',
  'export.arxiv.org','spacenews.com','www.nasaspaceflight.com','spaceflightnow.com',
  'www.planetary.org','phys.org','www.space.com','feeds.arstechnica.com',
  'arstechnica.com','www.universetoday.com')
$yaml = Get-Content -Raw $NetworkPolicy
foreach ($hostName in $expected) {
  if (($yaml | Select-String -AllMatches ([regex]::Escape("matchName: $hostName"))).Matches.Count -ne 1) { exit 1 }
}
```

- [ ] **Step 2: Run verifier and verify RED**

Expected: only four tracker hosts are present.

- [ ] **Step 3: Update CNP and deployment configuration**

Add only the validated exact hosts under a single tracker feed/body HTTPS rule. Add `TRACKER_EXTRACT_CRON`, `TRACKER_EXTRACT_BATCH_SIZE`, and `TRACKER_FLUKE_ENABLED=false` as non-secret env values. Do not modify resource requests or add containers.

- [ ] **Step 4: Run verifier and secret scan**

Run the verifier, `git diff --check`, and search changed GitOps files for token/API-key assignments. Expected: verifier passes and no secret value is present.

- [ ] **Step 5: Commit the GitOps task separately**

```powershell
git add gitops/apps/backend-springboot/network-policy.yaml gitops/apps/backend-springboot/deployment.yaml gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1 docs/plans/wp/tracker-infra-prework.md
git commit -m "deploy(tracker): allow Phase 1b collection egress"
```

---

### Task 8: Fluke filter and review audit schema

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V5__tracker_fluke_review.sql`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/FlukeEvaluationRow.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/ReviewRow.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerPhase1bSchemaTest.java`
- Modify: `docs/plans/wp/tracker-data-model.md`

**Interfaces:**
- One review row per `(event, reason)` trigger, allowing a later distinct review type for the same event.
- `review_queue` gains priority, fluke status/failure fields.
- `fluke_evaluation` stores one successful, version-stamped evaluation per review.

- [ ] **Step 1: Add failing schema and mapping tests**

Assert the new table/columns exist, duplicate `(review_queue.event_id, review_queue.reason)` fails while a different reason for the same event succeeds, and the review mapper preserves priority and fluke status.

- [ ] **Step 2: Run tests and verify RED**

Expected: missing columns/table and duplicate reviews currently allowed.

- [ ] **Step 3: Implement V5 and records**

```sql
ALTER TABLE review_queue ADD priority NUMBER(1) DEFAULT 0 NOT NULL;
ALTER TABLE review_queue ADD fluke_status VARCHAR2(10) DEFAULT 'PENDING' NOT NULL;
ALTER TABLE review_queue ADD fluke_fail_count NUMBER(2) DEFAULT 0 NOT NULL;
ALTER TABLE review_queue ADD fluke_last_error VARCHAR2(1000);
ALTER TABLE review_queue ADD CONSTRAINT ck_review_priority CHECK (priority BETWEEN 0 AND 2);
ALTER TABLE review_queue ADD CONSTRAINT ck_review_fluke_status CHECK (fluke_status IN ('PENDING','COMPLETE','FAILED'));
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
```

Update the data model and Java records with the exact fields.

- [ ] **Step 4: Run focused schema/repository tests**

Expected: V1-V5 apply, constraints enforce values, and existing review tests remain green after constructor updates.

- [ ] **Step 5: Commit Task 8**

```powershell
git add apps/backend/springboot-app/src/main/resources/db/migration/V5__tracker_fluke_review.sql apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerPhase1bSchemaTest.java docs/plans/wp/tracker-data-model.md
git commit -m "feat(tracker): add fluke review audit schema"
```

---

### Task 9: Structured fluke-filter contract

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/scoring/FlukeFilter.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/scoring/FlukeCandidate.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/scoring/FlukeResult.java`
- Create: `apps/backend/springboot-app/src/main/resources/tracker/prompt-fluke-system.txt`
- Create: `apps/backend/springboot-app/src/main/resources/tracker/fluke-tool-schema.json`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/scoring/FlukeFilterTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`

**Interfaces:**
- `FlukeResult evaluate(FlukeCandidate candidate)` returns verdict and exact quote. `FlukeCandidate` contains only the registered claim, current state, and verified article bodies required for independent evaluation; it is not the admin API DTO.
- Tool output contains only `verdict` and `evidence_quote`.
- Quote must match one supplied body after the existing whitespace normalization rule.

- [ ] **Step 1: Write failing contract tests**

```java
@Test
void acceptsMatchWithExactEvidenceQuote() {
    when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), eq("review_claim")))
            .thenReturn(Map.of("verdict", "MATCH", "evidence_quote", "The vehicle completed the test."));
    assertEquals("MATCH", filter.evaluate(reviewCase()).verdict());
}

@Test
void rejectsUnmatchedQuoteWithoutInventingAVerdict() {
    when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
            .thenReturn(Map.of("verdict", "MISMATCH", "evidence_quote", "Not in any article."));
    assertThrows(IllegalArgumentException.class, () -> filter.evaluate(reviewCase()));
}
```

Cover unknown verdict, missing fields, whitespace-normalized match, model property, prompt SHA, and no classifier reasoning in the user message.

- [ ] **Step 2: Run tests and verify RED**

Expected: filter classes/resources do not exist.

- [ ] **Step 3: Implement forced-tool filter and prompt**

The JSON schema is exact:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["verdict", "evidence_quote"],
  "properties": {
    "verdict": {"type": "string", "enum": ["MATCH", "MISMATCH"]},
    "evidence_quote": {"type": "string", "minLength": 1}
  }
}
```

The fixed prompt tells the model to independently test whether the supplied evidence supports the exact registered node/event/level claim, not to score or derive verification. Build input from repository-projected evidence bodies only.

- [ ] **Step 4: Run focused tests**

Expected: match/mismatch and all invalid-output cases behave deterministically with a mocked client.

- [ ] **Step 5: Commit Task 9**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/scoring apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java apps/backend/springboot-app/src/main/resources/tracker/prompt-fluke-system.txt apps/backend/springboot-app/src/main/resources/tracker/fluke-tool-schema.json apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/scoring/FlukeFilterTest.java
git commit -m "feat(tracker): add structured fluke filter"
```

---

### Task 10: Fluke job, retries, and StateUpdater integration

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/scoring/FlukeFilterJob.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/scoring/FlukeFilterJobTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/scoring/StateUpdater.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`
- Modify tests: `StateUpdaterTest.java`, `TrackerRepositoryTest.java`

**Interfaces:**
- Review insertion is idempotent by event id and starts with `fluke_status=PENDING`.
- `FlukeFilterJob.processPending()` stores MATCH/MISMATCH, raises mismatch priority, retries ordinary failures three times, and does not count cost deferral as a failure.
- No filter result advances or rejects state.

- [ ] **Step 1: Write failing job/state tests**

Cover:

```java
@Test
void mismatchIsPrioritizedButStillRequiresHumanDecision() {
    long reviewId = pendingReview();
    when(filter.evaluate(any())).thenReturn(new FlukeResult("MISMATCH", EXACT_QUOTE, MODEL, PROMPT_SHA));
    job.processPending();
    ReviewRow row = repository.findReviewById(reviewId).orElseThrow();
    assertEquals("MISMATCH", row.flukeResult());
    assertEquals(1, row.priority());
    assertEquals("PENDING", row.status());
    assertFalse(repository.findEventById(row.eventId()).stateAdvanced());
}
```

Also test MATCH priority 0, `CostLimitExceededException` leaves counters unchanged, third ordinary failure sets `fluke_status=FAILED`/priority 2, duplicate scans create one evaluation, and `tracker.fluke-enabled=false` creates no job bean while reviews still queue.

- [ ] **Step 2: Run tests and verify RED**

Expected: job and repository transitions are missing.

- [ ] **Step 3: Implement scheduling and transitions**

```java
@Scheduled(cron = "${tracker.fluke-cron:0 57 * * * *}")
@SchedulerLock(name = "tracker-fluke-filter", lockAtLeastFor = "PT1M")
public void runOnce() { processPending(); }
```

Gate the component with both `tracker.enabled=true` and `tracker.fluke-enabled=true`. Catch cost deferral separately. Repository writes evaluation + denormalized review fields in one transaction. Change `StateUpdater` to call `insertReviewIfAbsent(eventId, reason)` rather than unconditional insert; idempotency is keyed by `(eventId, reason)`.

- [ ] **Step 4: Run focused tests and backend regression**

Expected: all review/scoring/filter tests and the entire backend suite pass with no external call.

- [ ] **Step 5: Commit Task 10**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/scoring apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/scoring apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/domain/TrackerRepositoryTest.java
git commit -m "feat(tracker): queue and retry fluke evaluations"
```

---

### Task 11: Evidence-rich review API

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/ReviewCase.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/ReviewEvidence.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerAdminController.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/TrackerAdminControllerTest.java`

**Interfaces:**
- `GET /api/tracker/admin/review` returns `List<ReviewCase>` ordered priority descending then oldest first.
- Each case contains event/node/current/proposed level, score, verification, filter state/result, source count, and verified evidence records with title and URL.
- Reject requires a nonblank note; approve note is optional.

- [ ] **Step 1: Write failing API projection and decision tests**

```java
@Test
void pendingReviewIncludesEvidenceAndLevelContext() {
    ResponseEntity<List<ReviewCase>> response = controller.reviewQueue(ADMIN_TOKEN);
    ReviewCase item = response.getBody().getFirst();
    assertEquals("P1-ORBIT-REFUEL", item.nodeCode());
    assertEquals(6, item.currentLevel());
    assertEquals(8, item.claimedLevel());
    assertFalse(item.evidence().isEmpty());
    assertEquals(EXACT_QUOTE, item.evidence().getFirst().evidenceQuote());
}

@Test
void rejectionRequiresANote() {
    assertEquals(BAD_REQUEST, controller.decide(id, ADMIN_TOKEN, new Decision("REJECT", "")).getStatusCode());
}
```

Keep and extend `401`, `404`, and `409` tests.

- [ ] **Step 2: Run tests and verify RED**

Expected: endpoint returns bare `ReviewRow` and accepts blank rejection notes.

- [ ] **Step 3: Implement DTO query and validation**

Use one bounded review query plus one bounded evidence query for returned review ids, grouping in Java to avoid an N+1 query. Only `quote_verified='Y'` evidence is exposed. Sanitize reviewer notes by trim and enforce the existing 2,000-character DB limit before write.

- [ ] **Step 4: Run focused API/repository tests**

Expected: complete context, priority ordering, auth, conflicts, and note rules pass.

- [ ] **Step 5: Commit Task 11**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerAdminController.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/TrackerAdminControllerTest.java
git commit -m "feat(tracker): expose evidence-rich review cases"
```

---

### Task 12: Token-gated React review panel

**Files:**
- Create: `apps/frontend/react-app/src/tracker/ReviewQueue.tsx`
- Create: `apps/frontend/react-app/src/tracker/ReviewCaseCard.tsx`
- Create: `apps/frontend/react-app/src/tracker/ReviewQueue.test.tsx`
- Modify: `apps/frontend/react-app/src/tracker/api.ts`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.tsx`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.test.tsx`
- Modify: `apps/frontend/react-app/src/App.css`

**Interfaces:**
- `getReviews(token): Promise<ReviewCase[]>` sends `X-Tracker-Admin-Token`.
- `decideReview(id, token, decision, note)` supports `APPROVE`/`REJECT`.
- Token exists in React component state only; no storage or URL use.

- [ ] **Step 1: Write failing UI tests**

```tsx
it("renders priority, evidence, and current-to-proposed level after token submit", async () => {
  vi.stubGlobal("fetch", reviewFetchFixture());
  render(<ReviewQueue />);
  fireEvent.change(screen.getByLabelText(/admin token/i), { target: { value: "secret" } });
  fireEvent.click(screen.getByRole("button", { name: /load reviews/i }));
  expect(await screen.findByText("P1-ORBIT-REFUEL")).toBeInTheDocument();
  expect(screen.getByText("6 → 8")).toBeInTheDocument();
  expect(screen.getByText(EXACT_QUOTE)).toBeInTheDocument();
});

it("does not submit a rejection without a note", async () => {
  renderLoadedQueue();
  fireEvent.click(screen.getByRole("button", { name: /reject/i }));
  expect(screen.getByText(/rejection note is required/i)).toBeInTheDocument();
});
```

Add tests for `401`, `409`, empty state, MATCH/MISMATCH/FAILED badges, successful approve removal, and confirmation cancellation.

- [ ] **Step 2: Run tracker UI tests and verify RED**

Run:

```powershell
npm test -- --run src/tracker/ReviewQueue.test.tsx src/tracker/TrackerPage.test.tsx
```

Expected: review components and API contracts do not exist.

- [ ] **Step 3: Implement API types and review components**

Use a collapsed "검수 큐" section beneath the public timeline. Hold the token in `useState`, clear it on component unmount, and pass it directly to fetch calls. Case cards render links with `target="_blank" rel="noreferrer"`, exact quotes in `<blockquote>`, and explicit confirmation controls before mutation.

- [ ] **Step 4: Run focused tests, all Vitest, and TypeScript build**

```powershell
npm test -- --run
npm run build
```

Expected: all frontend tests and `tsc`/Vite production build pass.

- [ ] **Step 5: Commit Task 12**

```powershell
git add apps/frontend/react-app/src/tracker apps/frontend/react-app/src/App.css
git commit -m "feat(tracker-ui): add evidence review queue"
```

---

### Task 13: Phase 1b regression, documentation, and operational handoff

**Files:**
- Modify: `docs/plans/multiplanetary-tracker-execution-plan.md`
- Modify: `docs/plans/wp/tracker-pipeline-architecture.md`
- Create: `docs/runbooks/tracker-phase1b-validation.md`
- Modify: `.superpowers/sdd/progress.md` (ignored execution ledger; do not stage if ignored)

**Interfaces:**
- Master plan records embedding merge under Phase 2 and marks only implemented Phase 1b file work complete.
- Runbook provides exact Flux-only rollout and G1 evidence commands without `kubectl apply`.

- [ ] **Step 1: Run complete automated verification**

Backend:

```powershell
& $Maven '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
```

Frontend:

```powershell
npm test -- --run
npm run build
```

Git/manifests:

```powershell
git diff --check
& .\gitops\apps\backend-springboot\tests\tracker-egress-policy.ps1
```

Expected: every command exits 0. Record exact test totals rather than repeating the starting baseline.

- [ ] **Step 2: Review requirement coverage**

Create a table in the runbook mapping each requirement to evidence:

```text
12 feeds -> V4 seed test + config policy test + CNP verifier + 24h deployment observation
full text -> four fixtures + lifecycle integration test + real article body_extracted=Y
gap tolerance -> per-item/per-feed tests + 24h source outage observation
fluke filter -> mocked contract/job tests + staging review case
human review -> API/UI tests + approved/rejected staging cases
G1 -> real article E2E; remains open until recorded
```

Do not mark G1a or G1 complete from automated evidence alone.

- [ ] **Step 3: Update architecture/master documents**

Correct the Phase 1b summary contradiction by stating embeddings remain Phase 2 per WP1.5. Document the extraction and fluke jobs, schedules, status transitions, and API endpoints. The runbook must use Git commits/Flux reconciliation only and list Vault keys without values.

- [ ] **Step 4: Perform final self-review**

Inspect `git status`, task commits, migration ordering, secret scans, API backward compatibility, CNP/database host parity, and the untracked demo files. Confirm every task commit excludes `.claude/`, `application-demo.yml`, and `backfill-demo.json`.

- [ ] **Step 5: Commit documentation and handoff**

```powershell
git add docs/plans/multiplanetary-tracker-execution-plan.md docs/plans/wp/tracker-pipeline-architecture.md docs/runbooks/tracker-phase1b-validation.md
git commit -m "docs(tracker): document Phase 1b operations"
```

- [ ] **Step 6: Invoke branch finishing workflow**

Use `superpowers:verification-before-completion`, then `superpowers:finishing-a-development-branch`. Present PR/update options, but do not push, merge, modify Vault, or change production state without the user's chosen integration action.

---

## Plan self-review checklist

- Every Phase 1b requirement is assigned: collection/body/feeds in Tasks 1-7; filter/review in Tasks 8-12; full evidence and master-plan correction in Task 13.
- Embedding scope is explicitly deferred under the detailed WP1.5 rule rather than silently omitted.
- All new network behavior has both application policy and CNP work.
- Every LLM behavior is mock-testable and leaves state unchanged when disabled or cost-blocked.
- Every state mutation has an idempotency or concurrency test.
- No task commits a secret, demo resource, live API response, or site-specific parser rule.
- Types and names are consistent across tasks: `SourceDomainRow`, `FetchedPage`, `ExtractedArticle`, `ExtractionCandidate`, `FlukeCandidate`, `FlukeResult`, `FlukeEvaluationRow`, `ReviewCase`, and `ReviewEvidence`.
