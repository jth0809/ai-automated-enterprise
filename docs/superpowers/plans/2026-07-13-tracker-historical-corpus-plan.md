# Tracker Node-Neutral Historical Corpus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a copyright-minimal, node-neutral corpus of 180–250 historical space-settlement milestones from 1957 to the present using authoritative internet sources without storing third-party prose.

**Architecture:** Store one JSON object per line with independently authored factual summaries and reference metadata only. A strict Java validator and a transient PowerShell fingerprint tool enforce the no-copy policy; research proceeds pillar by pillar before any node or TRL/EGL mapping.

**Tech Stack:** Internet research using primary/authoritative web sources, JSONL, Java 21/Jackson, JUnit 5, PowerShell 7, SHA-256.

## Global Constraints

- Work in `.claude/worktrees/tracker-mvp` on `feat/tracker-mvp`.
- Preserve and exclude `.claude/`, `application-demo.yml`, and `backfill-demo.json` from commits.
- Store no source title, quotation, excerpt, article body, HTML, PDF, image, WARC, or attachment.
- `eventTitle`, `factSummary`, and `discoveryNote` must be independently written factual text.
- `factSummary` ≤500 characters; `locator` ≤300 characters; URL ≤1,000 characters.
- `eventTitle` and `actor` ≤200 characters; `discoveryNote` ≤1,000 characters; topic count ≤20; evidence count ≤8; each UTF-8 JSONL line ≤8 KiB.
- Candidate and evidence objects reject every unknown key; URLs reject credentials and sensitive query parameters.
- Every accepted source is opened and reviewed; search-result snippets are not evidence.
- Do not bypass authentication, paywalls, robots restrictions, or technical protection measures.
- Do not assign `nodeCode`, `claimedLevel`, or final `verificationLevel` in this corpus.
- Use official mission/agency/registry records first, peer-reviewed records second, and independent Tier 1–2 reporting for corroboration.
- Include setbacks, cancellations, rollbacks, and dormant-program evidence; do not build a progress-only history.
- Automated tests never call a live site.
- Internet research commits are split by pillar and contain only metadata/original summaries.

---

## File Structure

- Create `apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl`: canonical candidate corpus.
- Create `apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json`: reviewed source metadata used later by source-registry migration.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/HistoricalCandidate.java`: JSONL contract.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/HistoricalEvidenceReference.java`: reference-only evidence contract.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/CorpusReport.java`: immutable validation result.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/HistoricalCorpusValidator.java`: offline validation.
- Create `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalCorpusValidatorTest.java`: policy tests.
- Create `apps/backend/springboot-app/src/test/resources/tracker/backfill/historical-candidates-valid.jsonl`: positive fixture.
- Create `scripts/backfill/Get-SourceFingerprint.ps1`: bounded transient SHA-256 helper.
- Create `scripts/backfill/Test-HistoricalCorpus.ps1`: command-line corpus verifier.
- Create `docs/research/tracker-historical-corpus-log.md`: query batches, source decisions, rejection reasons, and exact counts.

---

### Task 1: Specify and validate the reference-only JSONL contract

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/HistoricalCandidate.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/HistoricalEvidenceReference.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/CorpusReport.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/HistoricalCorpusValidator.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalCorpusValidatorTest.java`
- Create: `apps/backend/springboot-app/src/test/resources/tracker/backfill/historical-candidates-valid.jsonl`

**Interfaces:**
- Produces: `CorpusReport HistoricalCorpusValidator.validate(Resource resource)` and immutable candidate/evidence records used by research and the later importer.

- [ ] **Step 1: Write failing record/validator tests**

```java
@Test
void validReferenceOnlyCandidatePasses() {
    CorpusReport report = validator.validate(
            new ClassPathResource("tracker/backfill/historical-candidates-valid.jsonl"));
    assertEquals(1, report.readyCount());
    assertTrue(report.errors().isEmpty());
}

@ParameterizedTest
@ValueSource(strings = {"quote", "evidenceQuote", "excerpt", "body", "html", "sourceTitle"})
void prohibitedSourceTextFieldsFail(String field) {
    assertTrue(validateLine(validLineWith(field, "copied text"))
            .errors().stream().anyMatch(e -> e.contains("prohibited field")));
}
```

- [ ] **Step 2: Add boundary and identity tests**

Cover duplicate `candidateId`, non-HTTPS or credential-bearing URL, sensitive URL query names, invalid 64-character lowercase hash, blank locator, all text/count/8-KiB bounds, unknown keys, invalid dates, empty evidence, and status outside `DISCOVERED`, `READY_FOR_MAPPING`, `REJECTED`.

- [ ] **Step 3: Run tests and verify RED**

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=HistoricalCorpusValidatorTest'
```

Expected: compile failure because the backfill contract classes do not exist.

- [ ] **Step 4: Implement records**

```java
public record HistoricalEvidenceReference(
        String sourceCode,
        URI url,
        String locator,
        LocalDate accessedOn,
        String contentSha256,
        String publicationPath,
        String factSummary) {}

public record HistoricalCandidate(
        String candidateId,
        String eventTitle,
        List<String> candidateTopics,
        String actor,
        LocalDate occurredOn,
        String occurredOnPrecision,
        List<HistoricalEvidenceReference> evidence,
        String discoveryStatus,
        String discoveryNote) {}
```

- [ ] **Step 5: Implement line-by-line validation**

Use Jackson `readTree` before record mapping so prohibited keys are rejected recursively. Accumulate line-numbered errors rather than stopping at the first invalid line. Return:

```java
public record CorpusReport(
        int totalCount,
        int readyCount,
        int rejectedCount,
        List<String> errors) {}
```

Normalize no source text. Validation is structural only. Require
`occurredOnPrecision` as `DAY`, `MONTH`, or `YEAR`. `MONTH` requires a day-1
sorting anchor and `YEAR` requires a January-1 sorting anchor so research never
invents a more exact historical date than the cited source supports.

- [ ] **Step 6: Run tests and verify GREEN**

Run the focused test. Expected: all contract tests pass.

- [ ] **Step 7: Commit contract and validator**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill apps/backend/springboot-app/src/test/resources/tracker/backfill
git commit -m "feat(tracker): validate reference-only historical corpus"
```

---

### Task 2: Add a bounded transient source fingerprint tool

**Files:**
- Create: `scripts/backfill/Get-SourceFingerprint.ps1`
- Create: `scripts/backfill/Test-SourceFingerprint.ps1`

**Interfaces:**
- Produces: `Get-SourceFingerprint.ps1 -Uri <https URI>` printing only JSON metadata: final URL, UTC access time, byte count, SHA-256, ETag, Last-Modified, and Content-Type.

- [ ] **Step 1: Write a local-server test**

The test starts a loopback `HttpListener`, serves a known 18-byte payload, invokes the tool with `-AllowLoopbackHttpForTests`, and asserts the SHA-256 and byte count. The switch is accepted only when the URI host is loopback and is rejected for every non-loopback HTTP URI. Add cases for ordinary non-HTTPS input, payload over 5 MiB, fourth redirect, and redirect to a different host.

- [ ] **Step 2: Run and verify RED**

```powershell
& ./scripts/backfill/Test-SourceFingerprint.ps1
```

Expected: failure because the tool does not exist.

- [ ] **Step 3: Implement the tool**

Required behavior:

```powershell
param(
  [Parameter(Mandatory)][uri]$Uri,
  [switch]$AllowLoopbackHttpForTests
)

if ($Uri.Scheme -ne 'https' -and
    !($AllowLoopbackHttpForTests -and $Uri.IsLoopback)) {
  throw 'HTTPS is required'
}
$maxBytes = 5MB
$maxRedirects = 3
```

Use `System.Net.Http.HttpClient` with automatic redirects disabled. Revalidate HTTPS and exact host on each redirect. Stream into `IncrementalHash`; do not write response bytes to disk and do not print content. Abort at `5 MiB + 1`.

Output shape:

```json
{"url":"http://127.0.0.1:8080/source","accessedOn":"2026-07-13","contentSha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","byteCount":3,"contentType":"text/plain","etag":null,"lastModified":null}
```

- [ ] **Step 4: Run tests and verify GREEN**

Expected: all local-only tests pass and no response body appears in output or temporary files.

- [ ] **Step 5: Commit the fingerprint tool**

```powershell
git add scripts/backfill/Get-SourceFingerprint.ps1 scripts/backfill/Test-SourceFingerprint.ps1
git commit -m "feat(tracker): fingerprint historical sources without storage"
```

---

### Task 3: Create the corpus file, source catalog, and CLI verifier

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl`
- Create: `apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json`
- Create: `scripts/backfill/Test-HistoricalCorpus.ps1`
- Create: `docs/research/tracker-historical-corpus-log.md`

**Interfaces:**
- Consumes: validator from Task 1 and fingerprint tool from Task 2.
- Produces: empty-but-valid corpus and source catalog ready for reviewed internet research commits.

- [ ] **Step 1: Create empty canonical files**

`historical-candidates-v1.jsonl` starts empty. Source catalog starts as `[]`. Research log records these exact target ranges:

```text
Pillar 1: 35–45
Pillar 2: 30–40
Pillar 3: 25–35
Pillar 4: 25–35
Pillar 5: 25–35
Pillar 6: 40–60
Total discovery corpus: 180–250
```

- [ ] **Step 2: Implement CLI validation wrapper**

The script runs the focused Maven validator test plus a small Java main or test entry point against the production JSONL, prints counts, and exits nonzero on errors or duplicate IDs. It must not require internet access.

- [ ] **Step 3: Run verifier**

Expected: zero records, zero errors, and an explicit warning that research targets are not yet met.

- [ ] **Step 4: Commit corpus scaffolding**

```powershell
git add apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json scripts/backfill/Test-HistoricalCorpus.ps1 docs/research/tracker-historical-corpus-log.md
git commit -m "data(tracker): initialize historical research corpus"
```

---

### Task 4: Research Pillar 1 — transport and propulsion

**Files:**
- Modify: `historical-candidates-v1.jsonl`
- Modify: `historical-source-catalog-v1.json`
- Modify: `docs/research/tracker-historical-corpus-log.md`

**Interfaces:**
- Produces: 35–45 transport/propulsion candidates with no node assignments.

- [ ] **Step 1: Search primary-source milestone families**

Run separate web searches for reusable launch, orbital propellant transfer, electric/nuclear propulsion, heavy EDL, surface ascent, crew abort/rescue, orbital logistics, and integrated transport. Search official NASA, ESA, JAXA, DARPA, FAA, mission-provider, and journal sites first.

- [ ] **Step 2: Add candidate records in batches of at most 10**

For each record, open the actual source, write a fresh factual summary, fingerprint the source, and append one JSONL line. Add a second independent source when available. Do not copy a source title; write a neutral event title.

- [ ] **Step 3: Search negative evidence**

Include at least five well-evidenced setbacks, cancellations, failed tests, or long program gaps. Label topics neutrally; do not assign `ROLLBACK` yet.

- [ ] **Step 4: Validate after every batch**

Run `Test-HistoricalCorpus.ps1`. Expected: zero structural errors and unique IDs.

- [ ] **Step 5: Fact-review the pillar batch**

Reopen every READY source. If URL, date, actor, or summary cannot be confirmed, set `discoveryStatus='REJECTED'` and record the reason rather than deleting the row.

- [ ] **Step 6: Commit Pillar 1 corpus**

```powershell
git add apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json docs/research/tracker-historical-corpus-log.md
git commit -m "data(tracker): research transport history"
```

---

### Task 5: Research Pillar 2 — life support and human health

Repeat the Task 4 workflow with a 30–40 candidate acceptance range. Search ECLSS closure, food growth, radiation countermeasures, long-duration medicine, waste/nutrient cycling, autonomous clinical care, and integrated survival demonstrations. Include BIOS/Biosphere analog evidence only when source facts are explicitly relevant to closed-loop operation; do not equate Earth analogs with flight operation.

Commit:

```powershell
git commit -m "data(tracker): research survival history"
```

---

### Task 6: Research Pillar 3 — habitat and infrastructure

Repeat the workflow with 25–35 candidates. Search construction, power grids, communication relays, thermal control, dust mitigation, and long-duration integrated habitat operations. Distinguish Earth analog construction from extraterrestrial deployment facts.

Commit:

```powershell
git commit -m "data(tracker): research habitat history"
```

---

### Task 7: Research Pillar 4 — resources and energy

Repeat the workflow with 25–35 candidates. Search ISRU prospecting/production, surface or space nuclear power, extreme-environment materials, local/recycled manufacturing, and integrated resource loops. Resource discovery alone is not resource production; preserve that distinction in `discoveryNote`.

Commit:

```powershell
git commit -m "data(tracker): research resource history"
```

---

### Task 8: Research Pillar 5 — robotics and autonomy

Repeat the workflow with 25–35 candidates. Search precursor construction robots, autonomous navigation/operations, physical maintenance/repair, fault recovery, and integrated autonomous base demonstrations. Generic terrestrial AI/robotics without explicit space application is excluded.

Commit:

```powershell
git commit -m "data(tracker): research autonomy history"
```

---

### Task 9: Research Pillar 6 — economics and governance

Repeat the workflow with 40–60 candidates. Search official launch market records, anchor/commercial contracts, private investment facts, legislation/treaty adoption and entry into force, licensing practice, insurance/standards, institutional rollback, and market failure. Prefer FAA, Congress.gov, UNOOSA/COPUOS, government procurement, regulator, and audited market records over commentary.

Commit:

```powershell
git commit -m "data(tracker): research economic governance history"
```

---

### Task 10: Cross-pillar deduplication and corpus acceptance

**Files:**
- Modify: corpus, source catalog, and research log.
- Test: `HistoricalCorpusValidatorTest.java`

- [ ] **Step 1: Add semantic duplicate report**

Generate candidates sharing normalized actor and event date within seven days. Review manually; preserve one candidate with multiple topic tags when records describe the same occurrence.

- [ ] **Step 2: Verify source-code catalog completeness**

Every `sourceCode` in JSONL must have one catalog entry with name, domain, `sourceType`, tier, and `feedActive=false` for new historical-only authorities.

- [ ] **Step 3: Run complete validator and count targets**

Expected: 180–250 total candidates, zero structural errors, and each pillar-topic range satisfied without forced low-quality rows.

- [ ] **Step 4: Run copyright/storage policy scan**

Search recursively for prohibited keys and unusually long strings:

```powershell
rg -n '"(quote|evidenceQuote|excerpt|body|html|sourceTitle)"' apps/backend/springboot-app/src/main/resources/tracker/historical-*
```

Expected: no matches. Validate that no JSONL line exceeds 8 KiB.

- [ ] **Step 5: Human corpus review gate**

Present counts, rejection reasons, source catalog, and ten representative candidates per pillar. Do not begin node mapping until the user approves the corpus and the `nodes-v1.0` registry is approved.

- [ ] **Step 6: Commit accepted corpus**

```powershell
git add apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json docs/research/tracker-historical-corpus-log.md apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalCorpusValidatorTest.java
git commit -m "data(tracker): accept node-neutral historical corpus"
```
