# Tracker Historical Corpus Research Log

Status: Pillars 1–2 complete; 70 candidates ready for mapping.

This log tracks discovery queries, opened sources, source decisions, rejection
reasons, and corpus counts. It never stores source titles, quotations, excerpts,
article bodies, HTML, PDF, images, or other source content.

## Acceptance targets

| Pillar topic family | Candidate range | Current |
|---|---:|---:|
| P1 transport and propulsion | 35–45 | 40 |
| P2 life support and human health | 30–40 | 30 |
| P3 habitat and infrastructure | 25–35 | 0 |
| P4 resources and energy | 25–35 | 0 |
| P5 robotics and autonomy | 25–35 | 0 |
| P6 economics and governance | 40–60 | 0 |
| **Total discovery corpus** | **180–250** | **70** |

The pillar figures are topic-family discovery targets, not node assignments.
One candidate may carry multiple topic tags but contributes once to the total.

## Research policy

1. Open the actual HTTPS source; search snippets are discovery aids only.
2. Prefer primary official records, then peer-reviewed records, then independent
   Tier 1–2 corroboration.
3. Write a neutral event title and a fresh factual summary of at most 500
   characters. Do not copy the source title or distinctive wording.
4. Run `Get-SourceFingerprint.ps1` transiently. Commit only URL, locator, access
   date, response metadata, and SHA-256; never response bytes.
5. Record setbacks, failures, cancellations, rollbacks, and dormancy evidence as
   well as advances.
6. Do not assign a node code, TRL/EGL level, event type, or final verification
   level during discovery.
7. A rejected candidate remains in the JSONL with a concise original reason.

## Query batches

| Batch | Pillar family | Query intent | Sources opened | Ready | Rejected | Notes |
|---|---|---|---:|---:|---:|---|
| P1-01 | P1 transport and propulsion | Reusable orbital transport, repeated recovery, and major setbacks | 15 | 10 | 0 | Nine stable NASA/FAA URLs accepted; JavaScript-only SpaceX shells and two unstable/oversized PDFs excluded as evidence. |
| P1-02 | P1 transport and propulsion | Orbital servicing, electric propulsion, nuclear thermal propulsion, and program setbacks | 8 | 10 | 0 | Eight stable NASA pages accepted; month/year precision retained where the official record did not state an exact day. |
| P1-03 | P1 transport and propulsion | Planetary EDL, heavy-landing precursors, surface ascent, and failed demonstrations | 11 | 10 | 0 | Ten stable NASA pages accepted; one JPL URL returned 403 to the fingerprint client and was replaced with an equivalent nasa.gov record without bypassing access controls. |
| P1-04 | P1 transport and propulsion | Crew abort/recovery and orbital docking/cargo handoff | 11 | 10 | 0 | Ten stable NASA pages accepted; one retired dated blog URL redirected to a generic archive and was replaced with a current NASA mission record. |
| P2-01 | P2 life support and human health | Regenerative air/water systems and crewed crop production | 10 | 10 | 0 | NASA and NTRS records document five ECLSS milestones and five cultivation outcomes; non-edible or installation-only evidence is explicitly bounded. |
| P2-02 | P2 life support and human health | Radiation measurement/protection and long-duration physiological evidence | 10 | 10 | 0 | Flight, ground-evaluation, and medical records separate monitoring from shielding and cohort observations from validated countermeasures. |
| P2-03 | P2 life support and human health | Solid-waste resource recovery and Earth-independent medical operations | 10 | 10 | 0 | Ground, suborbital, and ISS records distinguish collection from resource cycling and image acquisition from diagnosis or treatment. |

## Source decisions

| Source code | Authority | Decision | Reason |
|---|---|---|---|
| NASA | U.S. civil space agency | Accepted, Tier 1 agency | Mission pages and history records directly document NASA events and independently record commercial launch outcomes. |
| FAA | U.S. launch regulator | Accepted, Tier 1 agency | Official mishap correspondence records licensed launch outcome and corrective-action process. |
| SPACEX-WEB | Commercial launch provider | Excluded from batch | Direct requests returned only a JavaScript application shell, so the factual page content could not be reviewed or fingerprinted reliably. |
| NASA-HISTORY-PDF | NASA historical publication | Excluded from batch | Response exceeded the 5 MiB transient fingerprint limit. |
| NASA-SMA-PDF | NASA-hosted launch data sheet | Excluded from batch | Fingerprint request returned HTTP 500; stable NASA HTML records were used instead. |
| NASA-JPL-WEB | NASA laboratory web page | Excluded from batch | Browser review succeeded but the transient fingerprint request returned HTTP 403; an equivalent NASA release was used without access-control bypass. |
| NASA-DATED-BLOG | Retired dated NASA blog URL | Excluded from batch | The URL redirected to a generic blog archive that no longer contained the reviewed event record; a stable NASA mission article was used. |

## Rejection summary

| Reason | Count |
|---|---:|
| Source did not substantiate the candidate fact | 0 |
| HTTPS source unavailable | 0 |
| Duplicate occurrence | 0 |
| Outside space-settlement scope | 0 |
| Source access required authentication or bypass | 0 |
| Other documented reason | 0 |

## Verification history

| Date | Command | Result |
|---|---|---|
| 2026-07-13 | `scripts/backfill/Test-HistoricalCorpus.ps1` | 37/37 focused tests; 0 records, 0 errors; target warning emitted |
| 2026-07-13 | P1 batch 1 source review and transient fingerprinting | 10 candidates, 9 accepted URLs, no source bytes retained |
| 2026-07-13 | P1 batch 1 final fact review | All 9 unique URLs reopened; dates, actors, and outcomes confirmed for 10 READY records |
| 2026-07-13 | Date-precision contract test | 41/41 focused tests passed; DAY, MONTH, and YEAR anchors enforced |
| 2026-07-13 | P1 batch 2 source review and transient fingerprinting | 10 candidates, 8 accepted URLs, no source bytes retained |
| 2026-07-13 | P1 batch 2 final fact review | All 8 unique URLs reopened; dates, precision, actors, outcomes, and setbacks confirmed |
| 2026-07-13 | P1 batch 3 source review and transient fingerprinting | 10 candidates, 10 accepted URLs, one 403 source replaced, no source bytes retained |
| 2026-07-13 | P1 batch 3 final fact review | EDL scale exclusions and precursor/ascent boundaries recorded for all 10 READY records |
| 2026-07-13 | P1 batch 4 source review and transient fingerprinting | 10 candidates, 10 accepted URLs, one retired redirect replaced, no source bytes retained |
| 2026-07-13 | P1 batch 4 final fact review | Crew safety and orbital logistics facts, mixed outcomes, and exclusions confirmed |
| 2026-07-13 | P2 batch 1 source review and transient fingerprinting | 10 candidates, 10 accepted URLs, no source bytes retained |
| 2026-07-13 | P2 batch 1 final fact review | ECLSS closure boundaries and edible-yield limitations confirmed for all 10 READY records |
| 2026-07-13 | P2 batch 2 source review and transient fingerprinting | 10 candidates, 10 accepted URLs, no source bytes retained |
| 2026-07-13 | P2 batch 2 final fact review | Radiation protection levels, cohort limits, and unresolved medical risks confirmed |
| 2026-07-13 | P2 batch 3 source review and transient fingerprinting | 10 candidates, 10 accepted URLs, each response below 0.3 MiB, no source bytes retained |
| 2026-07-13 | P2 batch 3 final fact review | Waste collection/recovery and medical imaging/diagnosis/treatment boundaries confirmed |
| 2026-07-13 | P2 completion corpus validation | 42/42 focused tests; 70 READY records, 0 rejected, 0 errors |
