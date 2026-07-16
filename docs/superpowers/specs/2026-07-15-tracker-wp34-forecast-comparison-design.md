# Tracker WP3.4 Forecast Comparison Design

**Status:** Approved for implementation by the Phase 3 blanket approval on 2026-07-15.

**Goal:** Present model, transport-economics, crowd, and institutional outlooks side by side without implying that unlike targets answer the same question.

**Parent plans:**

- [Phase 3 kickoff](../plans/2026-07-14-tracker-phase3-kickoff-plan.md)
- [Master execution plan](../../plans/multiplanetary-tracker-execution-plan.md)

## 1. Decisions and scope

WP3.4 implements three comparison tracks:

1. `LANDING`: first successful crewed Mars surface landing.
2. `RETURN`: safe return to Earth after a crewed Mars mission.
3. `SETTLEMENT`: sustained Mars presence progressing toward a self-sufficient settlement.

Each track displays four estimate columns:

1. tracker model ETA,
2. transport-economics ETA,
3. crowd forecast,
4. institutional target.

The row heading is not an estimate column. A cell may state `NOT_APPLICABLE`, `UNDATED`, `AWAITING_AUTHORIZATION`, or `INSUFFICIENT_DATA`. The implementation must never substitute an unrelated year merely to fill a cell.

Out of scope:

- changing a node, event, readiness, snapshot, or ETA;
- treating the transport price threshold as a landing or settlement prediction;
- treating 100 people on Mars as a self-sufficient settlement;
- copying Metaculus page or API content into a production resource before authorization;
- activating LIVE_MODEL, LL2 Layer C promotion, or any LLM call;
- adding a pod or Kubernetes CronJob.

## 2. Approaches considered

### 2.1 Live Metaculus API only

This gives the freshest crowd observation, but the current API requires an authentication token and its data use is governed by access terms. The repository has neither a Vault key nor evidence of data-use authorization. Shipping this approach enabled would violate the security and provenance gates.

### 2.2 Reviewed snapshots only

This is deployment-safe and auditable, but it cannot produce the required multi-month moving average or low-frequency updates.

### 2.3 Hybrid, selected

Institutional references and crowd-question metadata are loaded from a reviewed, reference-only resource. Numeric Metaculus observations are stored only after a disabled authenticated collector passes three gates: feature enabled, terms approved, and a nonblank Vault-injected token. This supports the complete history and smoothing path without activating unauthorized data collection.

## 3. Comparison semantics

| Track | Tracker model | Transport economics | Crowd | Institution |
|---|---|---|---|---|
| LANDING | `NOT_APPLICABLE`; the model estimates settlement readiness, not first landing | `SUPPORTING`; the $200/kg scenario is an enabling condition, not a landing date | Metaculus post 3515 when authorized | NASA and SpaceX current crewed-Mars statements; undated if no official year exists |
| RETURN | `NOT_APPLICABLE`; no independent return ETA exists | `NOT_APPLICABLE`; the price threshold does not forecast safe return | `QUESTION_NOT_SELECTED` until a stable directly matching question is reviewed | NASA safe-return objective and SpaceX return-capability statement, both undated unless an official year is published |
| SETTLEMENT | `DIRECT_PROXY`; overall displayed ETA is the closest tracker output, with its self-sustaining-settlement definition visible | `SUPPORTING`; the $200/kg scenario is an input condition only | Metaculus post 39073 (100 people on Mars) as an explicitly weaker proxy when authorized | NASA Mars-infrastructure objective and SpaceX self-sufficient-city goal, undated unless an official year is published |

Current official-source interpretation at design time:

- NASA's 2026 Moon-to-Mars strategy specifies objectives and an evolving architecture, not a current crewed-Mars target year.
- SpaceX states that Mars cargo flights start no earlier than 2028 and describes first humans and a self-sufficient city, but does not give a current first-human or city completion year on the official Mars page.
- The old NASA `2030s` human-Mars objective is retained only as `LEGACY`, never as the current target.

## 4. Persistence model

Flyway V15 leaves the pre-created `external_forecast` table in place as numeric observation history and adds the metadata required to identify observations safely.

### 4.1 `forecast_reference`

This table stores reviewed target definitions and source provenance.

- `forecast_key`: stable primary key.
- `source_type`: `CROWD` or `INSTITUTIONAL`.
- `source_name`: `METACULUS`, `NASA`, or `SPACEX` for v1.
- `track_code`: `LANDING`, `RETURN`, or `SETTLEMENT`.
- `question`: locally authored short label.
- `target_definition`: exact local comparison meaning.
- `display_status`: `CURRENT`, `UNDATED`, `PRECURSOR`, `LEGACY`, or `AWAITING_AUTHORIZATION`.
- optional reviewed `forecast_year`, `forecast_year_low`, and `forecast_year_high` for institutional facts only.
- `relation_kind`: `DIRECT`, `PROXY`, `SUPPORTING`, `PRECURSOR`, or `REQUIREMENT`.
- `source_url`, `source_locator`, `accessed_on`, `content_sha256`, `fact_summary`, and `dataset_version`.
- `ingestion_mode`: `REVIEWED_REFERENCE` or `API`.

Metaculus reference rows contain stable post IDs, local labels, URLs, and authorization status only. They contain no community prediction value.

### 4.2 `external_forecast`

V15 adds nullable compatibility columns to the existing table:

- `forecast_key` referencing `forecast_reference`;
- `observation_sha256`;
- `observation_status` (`CURRENT` or `MISSING_AGGREGATE`);
- `smoothing_window_days`.

Rows with a nonnull `forecast_key` are unique by `(forecast_key, retrieved_on)`. The existing `forecast_year` remains numeric and nonnull, so missing API aggregates produce no numeric row. New application queries ignore legacy rows whose `forecast_key` is null.

### 4.3 `forecast_reference_import`

The import ledger stores dataset version, SHA-256, record count, and load time. Same-version/same-hash is a no-op; same-version/different-hash fails; a new version upserts by `forecast_key` and writes one audit row.

No WP3.4 table references a capability node or event.

## 5. Reviewed reference resource

`tracker/forecast-reference-v1.json` contains between 6 and 50 reviewed records. Initial records cover:

- NASA current landing, return, and Mars-infrastructure objectives with no invented year;
- SpaceX first-human, safe-return requirement, self-sufficient-city goal, and the 2028 cargo precursor;
- one NASA historical `2030s` record marked `LEGACY`;
- Metaculus landing and 100-person question metadata marked `AWAITING_AUTHORIZATION` with no forecast value.

The validator enforces a strict key allowlist, exact HTTPS hosts, enum values, date and year bounds, unique keys, source/type consistency, and a recursive prohibition on source body, quote, HTML, PDF, image, binary, node, score, readiness, and ETA fields. Summaries are reviewer-authored facts, not copied paragraphs.

## 6. Crowd collection and smoothing

### 6.1 Activation gate

The collector bean exists only when all three properties are true or present:

```text
tracker.enabled=true
tracker.metaculus-enabled=true
tracker.metaculus-terms-approved=true
tracker.metaculus-token=<nonblank Vault-injected token>
```

The deployment ships both booleans as `false` and does not reference a nonexistent secret. A later GitOps change may add the OCI Vault/External Secrets mapping only after the key and authorization evidence exist.

### 6.2 Network contract

- exact host: `www.metaculus.com`;
- HTTPS port 443 only;
- endpoint: `GET /api/posts/{postId}/?with_cp=true`;
- header: `Authorization: Token <token>`;
- redirects disabled;
- response limit: 1 MiB;
- two configured posts maximum in v1;
- weekly in-process schedule, Monday 05:17 UTC, ShedLock protected;
- no retry loop inside one run and no token in logs.

The parser accepts only a date/continuous question whose recency-weighted community aggregate exposes one finite center. Missing or inaccessible aggregation data is a normal no-mutation outcome. API shape drift, binary output, impossible dates, authentication failure, and non-2xx responses fail closed.

### 6.3 Moving average

For each crowd `forecast_key`, observations are at most one per UTC date. The stored `smoothed_year` is the arithmetic mean of point estimates in the inclusive 90-day window ending on the new observation date, rounded to one decimal. Observations older than 89 days before that date are excluded. Institutional records are never smoothed.

## 7. Public API

`GET /api/tracker/forecast-comparison` returns:

```json
{
  "status": "PARTIAL",
  "asOfDate": "2026-07-15",
  "smoothingWindowDays": 90,
  "crowdLiveStatus": "AUTHORIZATION_REQUIRED",
  "rows": [
    {
      "trackCode": "LANDING",
      "trackLabel": "첫 유인 화성 착륙",
      "definition": "사람이 화성 표면에 성공적으로 착륙",
      "model": { "status": "NOT_APPLICABLE", "year": null },
      "transport": { "status": "SUPPORTING", "year": 2030.4 },
      "crowd": { "status": "AWAITING_AUTHORIZATION", "year": null },
      "institutional": []
    }
  ]
}
```

Top-level status:

- `INSUFFICIENT_DATA`: no reviewed reference dataset;
- `PARTIAL`: references exist but one or more direct crowd observations are unavailable;
- `STALE`: all required sources exist but the newest crowd observation is older than 45 days;
- `CURRENT`: required direct/proxy crowd observations are at most 45 days old.

The response includes source labels, links, locators, retrieved/accessed dates, relation kind, and explanatory text. It does not include secrets, raw API responses, copied source content, or any field that changes scoring.

## 8. Frontend

`ForecastComparisonPanel` renders an accessible table with one row per track and four estimate columns. Each cell renders a value, a status label, and a short meaning note. Institutional cells may list multiple reviewed targets. Links open only exact provenance URLs.

The panel must visibly state:

- tracker ETA means self-sustaining settlement readiness;
- transport ETA means the declared $200/kg published-price scenario and is only an enabling condition;
- the crowd settlement question uses 100 people as a weaker proxy;
- undated and authorization-blocked states are evidence, not errors.

On narrow screens the table remains horizontally scrollable; semantic table markup is preserved.

## 9. GitOps and security

- add exactly `www.metaculus.com:443` to the existing tracker CNP;
- add `TRACKER_METACULUS_ENABLED=false`;
- add `TRACKER_METACULUS_TERMS_APPROVED=false`;
- add `TRACKER_METACULUS_CRON="0 17 5 * * MON"`;
- add `TRACKER_METACULUS_MAX_POSTS="2"`;
- do not add `TRACKER_METACULUS_TOKEN`, an ExternalSecret key, a pod, or a CronJob;
- keep LIVE_MODEL and all existing live collectors disabled;
- activation remains a later GitOps commit after Vault and terms evidence.

The egress verifier must require exact-host TCP 443, reject wildcard Metaculus hosts, verify both false gates, and confirm no token literal or secret reference was added.

## 10. Failure handling

- Invalid reference data aborts the import transaction.
- Same dataset version with changed bytes aborts before mutation.
- One Metaculus post failure does not prevent the other post from being attempted.
- Missing community aggregation records no numeric observation and leaves the latest valid value intact.
- Duplicate observation dates are idempotent only when the hash and value match; conflicting data fails.
- The public API remains available with `PARTIAL` or `INSUFFICIENT_DATA` when the collector is disabled.
- The dashboard does not fail as a whole when this endpoint fails; the comparison panel shows its own unavailable state.

## 11. Verification and G3 boundary

Completion requires:

1. V15 schema tests in H2 Oracle mode.
2. RED/GREEN validator, loader, repository, smoother, parser, client, job, service, and controller tests.
3. Frontend panel and TrackerPage integration tests.
4. Full backend suite, full frontend suite, production build, and browser console check.
5. GitOps verifier and kustomize render with no new workload.
6. `git diff --check`, placeholder scan, secret scan, and protected-fixture index check.
7. A first Phase 3 quarterly coherence report that records actual model, transport, crowd-authorization, institutional, K-index, and source-coverage states. `INSUFFICIENT_DATA` or `PARTIAL` is an acceptable honest result.

WP3.4 does not by itself activate Metaculus. G3 may be approved with the four-column panel operational and its crowd column honestly authorization-blocked.
