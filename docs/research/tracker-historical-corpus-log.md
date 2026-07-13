# Tracker Historical Corpus Research Log

Status: initialized; internet research not yet started.

This log tracks discovery queries, opened sources, source decisions, rejection
reasons, and corpus counts. It never stores source titles, quotations, excerpts,
article bodies, HTML, PDF, images, or other source content.

## Acceptance targets

| Pillar topic family | Candidate range | Current |
|---|---:|---:|
| P1 transport and propulsion | 35–45 | 0 |
| P2 life support and human health | 30–40 | 0 |
| P3 habitat and infrastructure | 25–35 | 0 |
| P4 resources and energy | 25–35 | 0 |
| P5 robotics and autonomy | 25–35 | 0 |
| P6 economics and governance | 40–60 | 0 |
| **Total discovery corpus** | **180–250** | **0** |

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

## Source decisions

| Source code | Authority | Decision | Reason |
|---|---|---|---|

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
