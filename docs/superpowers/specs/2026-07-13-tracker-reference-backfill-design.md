# Tracker Reference-Only Historical Backfill Design

**Status:** Approved direction, 2026-07-13  
**Program source:** `docs/plans/multiplanetary-tracker-execution-plan.md`  
**Predecessor:** `docs/superpowers/specs/2026-07-12-tracker-phase1b-design.md`

## 1. Purpose and decisions

The tracker needs a historical time series without copying third-party prose or
storing a material amount of source content. The current 47-event demo is only a
UI and mathematics fixture. It is not evidence-backed production data and must
not be promoted as such.

This design makes five binding decisions:

1. Historical evidence is **reference-only**. The repository stores URLs,
   source metadata, access dates, locators, content fingerprints, and
   independently written factual summaries. It stores no article body, source
   excerpt, image, PDF, HTML, WARC, or verbatim quotation.
2. The production backfill targets `nodes-v1.0`, an exact 35-node Phase 2 set.
   The existing 20 nodes remain `nodes-v0.1` and are not the final mapping
   surface.
3. Internet research starts before the 35-node set is final, but it produces a
   node-neutral candidate corpus. Node codes, TRL/EGL levels, and verification
   levels are assigned only after `nodes-v1.0` is approved.
4. Verification level is derived in code from registered source tier/type and
   publication path. A JSON value is an expected assertion checked against the
   derivation, never an authority.
5. Historical evidence uses its own table and UI label. It is not inserted into
   `article_classification.evidence_quote`, whose existing contract means a
   verbatim substring verified against a preserved article body.

Absolute legal risk cannot be guaranteed. The system minimizes copyright and
storage exposure by not retaining third-party expression and by keeping only
facts, identifiers, and original reviewer-authored summaries.

## 2. Scope and sequencing

The work is split into two independently reviewable packages that converge.

### Package A — `nodes-v1.0`

- Retain the 20 `nodes-v0.1` element-capability nodes unless review finds a
  concrete duplication or definition defect.
- Add 15 integration, safety, logistics, maintenance, and finish-line nodes.
- Include a finish-line-equivalent integration node for 26 months of continuous
  crewed operation without material resupply.
- Give every node a stable code, pillar, Korean and English name, scale type,
  one-sentence inclusion boundary, explicit exclusions, TRL/EGL anchors, and
  integration-node flag.
- Rebalance pillar weights so each pillar sums to 1.0. Weight approval follows
  the planned AHP pairwise-comparison process; candidate generation may use
  equal provisional weights but production import may not.
- Version the result as `nodes-v1.0` and pair it with a rubric version that
  contains the complete 35-node registry.

Node-set approval is a hard gate before candidate-to-node mapping. A node may
legitimately remain at level 0; the data process must not invent milestones to
fill every node.

### Package B — node-neutral historical research

- Search 1957 through the present for factual milestones relevant to the six
  pillars and likely integration topics.
- Build 180–250 candidates to allow rejection without forcing weak evidence.
- Prefer primary official sources and peer-reviewed records. Use independent
  Tier 1–2 reporting when an independent-verification claim is needed.
- Record no copied source text. The reviewer opens the source, verifies the
  fact, writes a fresh factual summary, and records a transient content hash.
- Do not assign `nodeCode`, `claimedLevel`, or final `verificationLevel` in this
  package.

The two packages may proceed in parallel. Package B uses topic tags rather than
node codes, so node-set changes do not invalidate source discovery.

## 3. Node-neutral candidate corpus

The canonical research artifact is
`src/main/resources/tracker/historical-candidates-v1.jsonl`. One JSON object is
stored per line for diff-friendly review.

```json
{
  "candidateId": "HC-2015-F9-LANDING",
  "eventTitle": "Falcon 9 first-stage landing demonstration",
  "candidateTopics": ["reusable launch", "launch economics"],
  "actor": "SpaceX",
  "occurredOn": "2015-12-21",
  "evidence": [
    {
      "sourceCode": "SPACEX",
      "url": "https://www.spacex.com/launches/orbcomm2",
      "locator": "mission result section",
      "accessedOn": "2026-07-13",
      "contentSha256": "64-lowercase-hex-digits",
      "publicationPath": "PRIMARY",
      "factSummary": "The launch vehicle's first stage landed after flight."
    }
  ],
  "discoveryStatus": "READY_FOR_MAPPING",
  "discoveryNote": "Potential partial-reuse milestone; not evidence of full vehicle reuse."
}
```

Constraints:

- `eventTitle` and `factSummary` are independently authored factual text, not
  copied source titles or paraphrases that preserve distinctive expression.
- `factSummary` is at most 500 characters and contains only the fact used by
  the tracker.
- `locator` is at most 300 characters and identifies a page, heading, table,
  or section without reproducing it.
- `contentSha256` is calculated transiently at review time. Source bytes are
  not committed or stored in the database.
- URLs must be HTTPS except for a documented historical source that has no
  HTTPS endpoint. No authentication token, cookie, paywalled body, or bypassed
  access control is stored.
- Candidate statuses are `DISCOVERED`, `READY_FOR_MAPPING`, or `REJECTED`.
  Rejected candidates remain in the audit corpus with a reason.

## 4. Mapped and reviewed backfill

After `nodes-v1.0` approval, candidates are mapped into
`src/main/resources/tracker/backfill-v1.json`. The file remains a flat list of
node claims because one historical event may support multiple capability nodes.
Each claim retains its candidate ID and evidence references.

```json
{
  "backfillId": "BF-P1-REUSE-LV-2015-01",
  "candidateId": "HC-2015-F9-LANDING",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-REUSE-LV",
  "eventType": "FLIGHT_TEST",
  "claimedLevel": 5,
  "actor": "SpaceX",
  "occurredOn": "2015-12-21",
  "expectedVerificationLevel": "INDEPENDENT",
  "eventTitle": "First-stage post-flight landing demonstration",
  "rubricJustification": "Partial-system flight evidence; not full-vehicle reuse.",
  "evidenceRefs": [
    "HC-2015-F9-LANDING#SPACEX",
    "HC-2015-F9-LANDING#SPACENEWS"
  ],
  "review": {
    "fact": "APPROVED",
    "rubric": "APPROVED",
    "reviewerNote": "Date, actor, event type, and level boundary checked."
  }
}
```

Acceptance target:

- 110–150 approved node claims from 180–250 candidates.
- Every achieved element node has enough milestones to explain its current
  state, normally two to five. Unachieved integration nodes may have zero state
  advancing events.
- Announcements and retrospectives may be retained for audit or timeline
  context but never advance state.
- No quota may override evidence quality or node inclusion boundaries.

Review is split into two explicit approvals:

1. **Fact review:** actor, event date, URL, source identity, locator, and factual
   summary.
2. **Rubric review:** node mapping, event type, claimed TRL/EGL, and exclusion
   boundaries.

Disagreement results in `REJECTED` or a corrected record. Reviewers do not
average disputed levels.

## 5. Historical evidence persistence

The next sequential Flyway migration creates a `historical_evidence` table and
an import audit table. Exact migration numbering is chosen when the
implementation plan is executed to avoid collision with intervening work.

### `historical_evidence`

```text
id
backfill_id
candidate_id
event_id
source_id
url
locator
accessed_on
content_sha256
publication_path
fact_summary
fact_review_status
rubric_review_status
reference_status
reviewer_note
created_at
```

Required constraints:

- Unique `(backfill_id, source_id, url)`.
- HTTPS URL by application validation.
- SHA-256 is 64 lowercase hexadecimal characters.
- `fact_summary` ≤500 characters; `reviewer_note` ≤2,000 characters.
- Only rows with both reviews `APPROVED` participate in verification or UI
  source counts.
- `reference_status` is `APPROVED`, `STALE`, or `REJECTED`; only `APPROVED`
  references participate in verification or source counts.
- No column exists for source body, source title, excerpt, HTML, or attachment.

### `backfill_import`

```text
dataset_version
dataset_sha256
node_set_version
rubric_version_id
imported_at
record_count
```

The import audit prevents silent replacement and supports idempotent imports on
a database that already contains live events. The loader no longer uses
`countEvents() > 0` as a global no-op condition.

## 6. Import data flow

```text
candidate corpus + approved mappings
  -> schema and cross-file validation
  -> source_registry resolution
  -> event natural-key upsert
  -> historical_evidence upsert
  -> VerificationDeriver over approved source metadata
  -> compare with expectedVerificationLevel
  -> CONFIRMED event and node-state replay
  -> historical year-end snapshots
  -> backfill_import audit row
```

The import runs in one transaction per dataset version. Unknown source codes,
unknown node codes, invalid enum values, duplicate IDs, missing reviews,
verification mismatches, or dataset hash mismatches roll back the complete
dataset import. Network access is never required at application startup.

Historical state advancement remains deterministic and code-only. The loader
does not call an LLM or fluke filter. Dual human approval is the historical
equivalent of the live review gate.

Rollbacks and dormancy are not modeled as monotonic maximum levels. The mapped
format supports explicit `ROLLBACK`, `PROGRAM_CANCELLATION`, and dormancy audit
records, and snapshot replay applies state transitions in date order.

## 7. Source registry and verification

Every evidence reference resolves to `source_registry`. Missing historical
authorities are added as non-feed rows with `feed_active='N'`. They do not
receive `source_domain` egress entries because startup import reads local JSON
and performs no network request.

Examples likely to require non-feed rows include UNOOSA, FAA, DARPA,
Congress.gov, DOE or predecessor archives, and other official registries found
during research. New source type or tier assignments require evidence and a
schema-compatible classification; they are not inferred from the actor name.

Verification uses the existing code hierarchy:

- agency primary evidence may establish `OFFICIAL`;
- journal evidence may establish `PEER_REVIEWED`;
- two distinct non-wire Tier 1–2 sources may establish `INDEPENDENT`;
- reprints of the same release do not count as independent evidence.

The expected level in the mapping is an assertion used by tests. A mismatch is
an import error, not an opportunity to override the derivation.

## 8. API and UI behavior

Live and historical evidence are visibly distinct:

- live classification: `원문 인용` with the existing verbatim quote;
- historical evidence: `인간 검수 사실 요약` with the reviewer-authored
  `fact_summary` and external source link.

Historical evidence is rendered as ordinary text, never as a quotation or
`blockquote`. Public timeline and admin review queries combine verified live
sources and approved historical references for source counts. API DTOs expose
an evidence kind (`VERBATIM` or `HISTORICAL_REFERENCE`) so clients cannot
mislabel a summary as a quotation.

If a URL becomes unavailable or its fingerprint changes, the evidence is marked
`STALE` by a later maintenance workflow and queued for re-review. It is not
automatically deleted and does not silently reduce state. Link-health automation
is outside this initial implementation; the schema and status semantics must
leave room for it.

## 9. Internet research protocol

Research prioritizes primary and authoritative sources:

1. official mission and agency records;
2. official treaty, legislation, licensing, and registry records;
3. peer-reviewed papers and journal records;
4. independent Tier 1–2 specialist reporting for corroboration.

The researcher records only metadata and original factual summaries. Access
controls, robots restrictions, authentication, paywalls, and technical
protection measures are never bypassed. Search-result snippets are not treated
as evidence. A source is accepted only after its actual page or document is
opened and reviewed.

Research proceeds by pillar and time period, not by trying to prove a
preselected ETA. Negative and rollback events are intentionally searched to
reduce survivorship and progress-only bias.

## 10. Automated verification

The implementation must add deterministic tests for:

- exactly 35 active `nodes-v1.0` nodes and pillar weight sums;
- candidate JSONL syntax, unique IDs, URL policy, summary and locator bounds,
  and no prohibited source-text fields;
- approved mapping references, node/rubric version parity, enums, level ranges,
  and dual-review gates;
- verification derivation from evidence metadata, including wire-reprint and
  duplicate-source rejection;
- transactional rollback on any invalid item;
- idempotent import into empty and already-populated databases;
- state replay with rollback and non-state events;
- historical source counts and UI labels without blockquote rendering;
- dataset hash audit and second-import no-op;
- fresh H2 replay, year-end snapshots, and finite-or-explicit-null ETA outputs.

No automated test invokes a live LLM or depends on a live external website.
Internet validation evidence is captured in the candidate corpus and reviewed
separately from CI.

## 11. Delivery sequence and gates

1. Approve this design.
2. Produce the detailed implementation and research plan.
3. Draft and approve `nodes-v1.0` before final mapping.
4. Collect the node-neutral candidate corpus from the internet in parallel.
5. Implement schema, validator, importer, API, and UI support using fixtures.
6. Map candidates to the approved 35-node rubric.
7. Complete fact and rubric reviews.
8. Import into fresh H2 and inspect timeline, state, and ETA outputs.
9. Commit code and human-reviewed data separately.
10. Deploy only through GitOps; operational gates remain open until real
    deployment evidence is recorded.

The untracked `backfill-demo.json` remains a local demo fixture and is excluded
from production data commits. The empty `backfill-v0.json` is not promoted as
historical truth.
