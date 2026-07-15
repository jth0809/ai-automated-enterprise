# Tracker WP3.3 Transport-Economics ETA and B-Pair Coherence Design

**Status:** Approved direction, 2026-07-15
**Program source:** `docs/plans/multiplanetary-tracker-execution-plan.md`
**Phase plan:** `docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md`
**Predecessor:** `docs/superpowers/plans/2026-07-14-tracker-wp31b-launch-library.md`

## 1. Purpose and binding decisions

WP3.3 adds a second, independent estimate for Pillar 1 transport economics and
the first matched-pair coherence check between Layer B measurements and Layer C
readiness. It does not replace the capability-tree ETA and does not estimate the
arrival of a multiplanetary civilization.

The following decisions are binding:

1. The central declared target is **USD 200/kg to LEO in constant 2025 dollars**.
   The disclosed sensitivity range is **USD 100-500/kg**. These values are
   assumptions, not measured settlement thresholds.
2. The input price is a published launch-service price divided by the matching
   published maximum LEO payload. It is labelled a **published-price lower-bound
   proxy**, never internal cost, transaction price, or typical delivered price.
3. The initial experience curve is limited to the operational Falcon launch
   family. Falcon 9 and Falcon Heavy observations are eligible only when price
   and payload refer to the same service configuration. Pre-operational estimates
   and Starship targets are not fit inputs.
4. Wright's law is fit against cumulative completed Falcon-family orbital
   launches. Global launch cadence remains a dashboard indicator but is not used
   as a substitute for family-specific experience.
5. Three or four valid price observations produce a visible `PROVISIONAL`
   projection with a wide sensitivity interval. Five or more produce
   `ESTABLISHED` data sufficiency. Low goodness-of-fit is disclosed and never
   used as a hard rejection gate.
6. B-pair divergence can create an internal alert, deterministic event-audit
   sample, and a read-time widening overlay for Pillar 1 ETA. It must not change
   a node level, readiness value, source verification, event classification, or
   persisted `pillar_snapshot` row.
7. LIVE_MODEL stays disabled. WP3.3 performs no LLM call and adds no egress host
   or secret.

## 2. Epistemic boundary

The model is a scenario projection under declared assumptions. It is not a
forecast of provider cost or a probability that USD 200/kg will be achieved.

Published price divided by advertised maximum payload is optimistic by design:
missions often do not fill maximum payload, orbit and integration requirements
change service price, reusable and expendable configurations differ, and
provider margin is unknown. The public API and UI therefore carry these fixed
labels:

- `basis`: `PUBLISHED_PRICE`
- `priceMeaning`: `PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD`
- `projectionLabel`: `Declared-assumption scenario; not provider internal cost`
- `intervalKind`: `ASSUMPTION_SENSITIVITY`

The USD 100-500 interval is not an 80% probability interval. Phase 4 Monte Carlo
work may add a model-internal probability interval later, but it must remain
separate from this assumption-sensitivity interval.

## 3. Eligible observations and normalization

### 3.1 Price observation gate

An observation is eligible only when all of the following are true:

- the launch vehicle was operational on the observation date;
- the source publishes a numeric service price and numeric maximum LEO payload;
- price and payload describe the same vehicle and service configuration;
- the source is the provider, a government agency, or a government-contracted
  compendium with a stable URL;
- the observation records the publication date or effective year, access date,
  source locator, SHA-256 content fingerprint, and a reviewer-authored factual
  summary;
- no source body, quotation, HTML, PDF, image, or binary is stored.

Provider targets, analyst estimates, mission-specific government contract totals,
and prices that bundle spacecraft or mission services are rejected as fit inputs.
They may be retained outside the model as reference annotations.

When more than one eligible Falcon-family configuration exists in a year, the
annual frontier is the lowest eligible constant-dollar price per kilogram. A
configuration whose advertised price does not cover its advertised maximum LEO
performance cannot become the frontier.

### 3.2 Constant-dollar conversion

Nominal values are converted to constant 2025 USD with annual U.S. CPI-U,
U.S. city average, all items:

```text
nominal_usd_per_kg = published_price_usd / max_leo_payload_kg
real_2025_usd_per_kg = nominal_usd_per_kg * CPI_2025 / CPI_observation_year
```

The CPI series is a versioned numeric reference input with the same provenance
and no-content-storage rules. Re-running an immutable dataset version with a
different canonical hash is an error.

### 3.3 Launch-volume series

The experience variable is cumulative completed Falcon 9 plus Falcon Heavy
orbital launches through the end of each observation year. `Success`, `Failure`,
and `Partial Failure` count as production/operational experience. Scheduled,
cancelled, suborbital, and Starship test flights do not count.

Annual counts require at least three complete years. The existing Launch Library
2 client supplies future annual counts. The initial historical series is an
immutable, reference-only numeric dataset reviewed before import.

The canonical resource is `tracker/transport-economics-v1.json`. Its only
top-level keys are `datasetVersion`, `assumption`, `cpi`, `annualLaunchCounts`,
and `priceObservations`. The validator rejects unknown keys at every level and
recomputes annual frontiers, constant-dollar values, and cumulative launches
instead of trusting redundant JSON outputs.

## 4. Wright projection

### 4.1 Fit

For annual frontier observations `i`, fit ordinary least squares in log space:

```text
ln(price_i) = alpha + beta * ln(cumulative_launches_i)
```

All values must be finite and strictly positive, years must be unique after
frontier selection, and cumulative launches must strictly increase. The fit
statuses are:

| Condition | Status | ETA |
|---|---|---|
| Fewer than 3 valid observations or annual-count years | `INSUFFICIENT_DATA` | none |
| 3-4 valid observations | `PROVISIONAL` | visible when `beta < 0` |
| 5+ valid observations | `ESTABLISHED` | visible when `beta < 0` |
| `beta >= 0` | `NON_DECLINING` | none |
| Central target already met by an eligible observation | `REACHED` | first eligible reach year |
| Central ETA exceeds the 150-year display horizon | `BEYOND_HORIZON` | null, horizon disclosed |

`R² < 0.50` adds `WEAK_FIT`; it does not suppress a finite projection. This is
the explicit partial-evidence rule for WP3.3: sparse but valid evidence is shown
with qualification instead of being rounded down to no evidence.

For target price `P_t` and `beta < 0`, required cumulative launches are:

```text
N_t = exp((ln(P_t) - alpha) / beta)
```

### 4.2 Converting launch volume to a year

The model does not extrapolate accelerating cadence indefinitely. It holds a
recent observed cadence constant:

- central cadence: trailing 5-year arithmetic mean, or trailing 3-year mean
  when only 3-4 complete years exist;
- fast cadence: maximum available mean among complete 3-, 5-, and 10-year
  windows;
- slow cadence: minimum available mean among those windows.

For each scenario:

```text
eta_year = as_of_year + max(0, N_t - current_cumulative_launches) / annual_cadence
```

The central projection uses USD 200/kg and central cadence. The earliest
sensitivity edge uses USD 500/kg and fast cadence. The latest edge uses
USD 100/kg and slow cadence. Each edge is independently capped at 150 years;
an edge beyond the cap is returned as null with `beyondHorizon=true` rather than
silently clamped to a false numeric date.

Database values retain one decimal year. The UI rounds to the nearest year.

## 5. Persistence and immutable input

Flyway V12 introduces five bounded tables and one import ledger.

### `transport_economics_assumption`

Stores the immutable, versioned scenario contract:

- assumption version and model version;
- central/easy/hard target USD/kg values;
- constant-dollar basis year, maximum horizon, weak-fit R² threshold, and
  divergence widening factor;
- creation timestamp.

`transport-assumptions-v1` fixes values at `200`, `500`, `100`, `2025`, `150`,
`0.50`, and `1.50`, respectively. Projection rows reference this version.

### `transport_price_observation`

Stores audited numeric inputs:

- observation year, vehicle family and variant;
- published price, matching maximum LEO payload, nominal USD/kg;
- CPI observation value, CPI 2025 value, real 2025 USD/kg;
- cumulative Falcon-family launches;
- source label, URL, locator, access date, content SHA-256, and factual summary.

The natural key is `(observation_year, vehicle_family, vehicle_variant)`. Numeric
values are positive, hashes are lowercase 64-hex, URLs are HTTPS, and text/body
fields are prohibited.

### `transport_economics_projection`

Stores one reproducible result per `(as_of_date, assumption_version)`:

- status, sufficiency tier, observation count, `alpha`, `beta`, `R²`;
- current cumulative launches and central/fast/slow cadence;
- central/easy/hard targets and required cumulative launches;
- central, earliest, and latest ETA years plus horizon flags;
- fixed price-basis year, interval kind, model version, and reason code.

The initial assumption version is `transport-assumptions-v1` and the model
version is `wright-falcon-v1`. `assumption_version` is a foreign key to
`transport_economics_assumption`.

### `transport_coherence_report`

Stores one report per completed UTC quarter end. A job running on the first day
of a quarter reports the immediately preceding quarter:

- Layer B price, cadence, and combined directions;
- Layer C Pillar 1 direction and source snapshot date;
- coherence state, polarity, consecutive-quarter streak, alert flag;
- interval widening factor and the first divergent quarter.

### `transport_coherence_sample`

Stores up to ten deterministic Pillar 1 event IDs selected for audit, with
`PENDING` or `REVIEWED` status, reviewer note, and review timestamp. Selection
does not reopen, reject, or rescore the event.

### `transport_economics_import`

Stores immutable dataset version, canonical SHA-256, price-observation count,
annual-launch-record count, CPI-record count, and load timestamp. Dataset
`transport-economics-v1` is idempotent for the same hash and rejected for a
changed hash.

The existing `layer_b_metric` table remains the public numeric-indicator surface.
The importer mirrors annual frontier price and annual Falcon-family launch count
as `LEO_PUBLISHED_PRICE_FRONTIER_REAL_2025` and
`ANNUAL_FALCON_FAMILY_LAUNCH_COUNT` while the projection uses the richer V12
tables.

## 6. B-pair coherence

### 6.1 Direction rules

The quarterly check compares only the approved matched pair:

```text
Layer B published-price frontier + Falcon-family cadence
    versus
Layer C Pillar 1 readiness trend
```

Price direction uses Wright `beta`: below `-1e-6` is `ADVANCING`, above `1e-6`
is `REGRESSING`, otherwise `FLAT`. Cadence direction uses an OLS slope over the
latest complete five years, or three years when only 3-4 exist, on
`ln(annual_launches + 1)`: greater than `0.01` is `ADVANCING`, less than `-0.01`
is `REGRESSING`, otherwise `FLAT`.

Layer B direction is the shared non-flat direction, the non-flat direction when
the other input is flat, or `MIXED` when price and cadence oppose. Layer C uses
the latest Pillar 1 `trend_used`: greater than `1e-6` is `ADVANCING`, less than
`-1e-6` is `REGRESSING`, otherwise `FLAT`.

Missing inputs yield `INSUFFICIENT_DATA`; `MIXED` and insufficient states never
trigger automatic effects.

### 6.2 Persistence before alert

Different non-mixed B and C directions create a first-quarter `WATCH` with
polarity `B_AHEAD` or `C_AHEAD`. Only the same polarity in two consecutive UTC
quarters becomes `DIVERGENT` and activates all three concept-required effects:

1. internal warning log and an alert in the tracker operations projection;
2. deterministic audit sample of up to ten confirmed Pillar 1 events from the
   watched interval, ordered by impact descending, occurrence date descending,
   then event ID;
3. Pillar 1 ETA interval widening factor `1.5`.

A coherent, mixed, or insufficient following quarter resets the streak. There is
no state freeze and no automatic human-review decision.

### 6.3 Non-destructive interval overlay

The widening factor is applied only while constructing the public Pillar 1 API
projection:

```text
widened_low  = eta - 1.5 * (eta - original_low)
widened_high = eta + 1.5 * (original_high - eta)
```

Results remain inside the current-to-150-year display horizon. The original
`pillar_snapshot.eta_low`, `eta_high`, readiness, and trend fields are never
updated by coherence code. The API identifies the overlay and report quarter so
the displayed change is auditable.

## 7. Jobs and configuration

Two small in-process operations reuse the backend deployment and ShedLock:

- `tracker-transport-projection`: monthly on day 8 at `03:47 UTC`, after the LL2
  `03:17 UTC` import; lock at most 10 minutes;
- `tracker-coherence`: quarterly on day 1 at `03:00 UTC`, preserving the existing
  architecture schedule; lock at most 10 minutes.

Both require `tracker.enabled=true` and
`tracker.transport-economics-enabled=true`. GitOps and application defaults are
`false`. A local visual-review process may enable them with environment overrides;
the protected local test profiles are not modified.

Configuration keys are:

```text
TRACKER_TRANSPORT_ECONOMICS_ENABLED=false
TRACKER_TRANSPORT_PROJECTION_CRON=0 47 3 8 * *
TRACKER_COHERENCE_CRON=0 0 3 1 */3 *
TRACKER_TRANSPORT_TARGET_USD_PER_KG=200
TRACKER_TRANSPORT_TARGET_EASY_USD_PER_KG=500
TRACKER_TRANSPORT_TARGET_HARD_USD_PER_KG=100
```

Runtime configuration must match the versioned assumption row. A mismatch
causes the job to fail closed without overwriting the latest valid projection.
No Kubernetes CronJob, pod, secret, or CNP rule is added.

## 8. API and UI

`GET /api/tracker/transport-economics` returns the latest projection, including
all declared targets, constant-dollar basis, status, qualification flags,
observation count, fit values, ETA scenarios, fixed labels, and latest coherence
state.

`GET /api/tracker/coherence/transport` returns the latest public coherence
summary. Audit sample details remain on the authenticated admin surface.

`GET /api/tracker/admin/coherence/transport` returns the latest report and audit
samples. `POST /api/tracker/admin/coherence/transport/samples/{id}` accepts a
nonblank reviewer note of at most 2,000 characters and marks one pending sample
`REVIEWED`; repeat or unknown decisions return conflict/not-found without changing
the event.

`GET /api/tracker/pillars` adds `baseEtaLow`, `baseEtaHigh`, `etaLow`, `etaHigh`,
`coherenceAdjusted`, and `coherenceReportPeriod`. For Pillar 1, `etaLow` and
`etaHigh` contain the non-destructive overlay when active; `baseEtaLow` and
`baseEtaHigh` always expose persisted snapshot values. Other pillars return equal
base and displayed bounds and `coherenceAdjusted=false`.

WP3.3 adds a compact card below the existing Layer B indicators:

- central ETA or `자료 부족` / `하락 추세 미확인` / `2175+`;
- `중앙 가정 $200/kg · 민감도 $100-$500/kg (2025 USD)`;
- `공표가÷동일 구성 최대 LEO 탑재량 — 실제 원가 아님`;
- `잠정 N개 관측` or established observation count;
- sensitivity years and active coherence warning, if any.

The four-column comparison panel remains WP3.4. WP3.3 only supplies its transport
economics column and a locally visible compact card.

## 9. Failure behavior

- Invalid or hash-mismatched input aborts the whole import transaction.
- Insufficient data persists an explanatory projection instead of throwing or
  inventing an ETA.
- A non-declining finite fit persists `NON_DECLINING` and no ETA.
- A failed monthly or quarterly run leaves the latest valid projection/report
  untouched and emits an operational error.
- Duplicate schedule execution is prevented by ShedLock and database unique
  constraints.
- No failure path changes Layer C state or activates LIVE_MODEL.

## 10. Verification and acceptance

Implementation is complete only when all of the following are proven:

1. Pure-math tests cover exact synthetic Wright fits, sparse 3-point provisional
   fits, weak-R² disclosure without rejection, non-declining data, reached
   targets, and each independent horizon edge.
2. Validator tests reject configuration mismatch, nonmatching price/payload,
   pre-operational targets, nonpositive values, non-increasing cumulative counts,
   prohibited source-content fields, bad hashes, and changed immutable imports.
3. Repository and migration tests prove V12 constraints, idempotent imports,
   reproducible projections, and deterministic audit sampling.
4. Coherence tests prove first-quarter watch, same-polarity second-quarter alert,
   streak reset, mixed/insufficient no-op, 1.5 overlay math, and byte-for-byte
   preservation of persisted Pillar 1 snapshots and node state.
5. API and frontend tests prove all honesty labels, assumption values, provisional
   status, no fabricated ETA, and accessible rendering.
6. GitOps verification proves both jobs disabled by default and no new egress,
   pod, CronJob, or secret.
7. Full backend regression, frontend tests, production build, `git diff --check`,
   GitOps render, and local browser inspection pass with LIVE_MODEL disabled.
8. A first local quarterly report is persisted and visible. Its result may be
   coherent, watch, divergent, or insufficient; the software must report the
   evidence honestly rather than force a preferred state.

## 11. Out of scope

- Starship target prices as observations;
- actual provider marginal cost estimation;
- global civilization ETA derived from launch statistics;
- automatic node scoring, event rejection, state freeze, or snapshot mutation;
- Phase 4 Monte Carlo uncertainty and structural-break inference;
- WP3.4 Metaculus, institutional forecasts, and four-column panel.
