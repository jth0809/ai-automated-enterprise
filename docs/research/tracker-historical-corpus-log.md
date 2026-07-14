# Tracker Historical Corpus Research Log

Status: Pillars 1–6 complete; exactly 210 candidates ready for mapping.

This log tracks discovery queries, opened sources, source decisions, rejection
reasons, and corpus counts. It never stores source titles, quotations, excerpts,
article bodies, HTML, PDF, images, or other source content.

## Acceptance targets

| Pillar topic family | Candidate range | Current |
|---|---:|---:|
| P1 transport and propulsion | 35–45 | 40 |
| P2 life support and human health | 30–40 | 30 |
| P3 habitat and infrastructure | 25–35 | 30 |
| P4 resources and energy | 25–35 | 30 |
| P5 robotics and autonomy | 25–35 | 30 |
| P6 economics and governance | 40–60 | 50 |
| **Total discovery corpus** | **exactly 210** | **210** |

The pillar figures are topic-family discovery targets, not node assignments.
One candidate may carry multiple topic tags but contributes once to the total.
The first P6 pass retained 30 high-confidence candidates rather than adding weak
or duplicative evidence. After `nodes-v1.0` approval, the operating target was
clarified from the broad 180–250 range to exactly 210. Two focused P6 batches add
20 official-record candidates covering commercial service progression,
international operating agreements, program retirement, and safety setbacks.
The corpus remains node-neutral; the added records contain no node code or level.

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
| P3-01 | P3 habitat and infrastructure | Surface shelter, orbital habitat assembly, expandable structures, and construction analogs | 9 | 10 | 0 | Records separate temporary cabins, orbital habitats, terrestrial analogs, and subscale prints from commissioned surface construction. |
| P3-02 | P3 habitat and infrastructure | Integrated power systems and delay/disruption-tolerant communications | 9 | 10 | 0 | Isolated generators, orbital grids, analog buses, routed protocols, relays, and point-to-point optical links are explicitly distinguished. |
| P3-03 | P3 habitat and infrastructure | Thermal control, dust mitigation, fault recovery, and long-duration habitat analogs | 8 | 10 | 0 | Operational orbital systems, component lunar tests, and terrestrial analog durations remain distinct from integrated surface habitation. |
| P4-01 | P4 resources and energy | ISRU integration, resource detection, oxygen production, sample handling, and program setbacks | 10 | 10 | 0 | Detection is separated from extraction and production; Earth analogs, small flight experiments, and limited lunar operations retain explicit scale and outcome boundaries. |
| P4-02 | P4 resources and energy | Nuclear power concepts, long-duration material exposure, and construction-material research | 9 | 10 | 0 | Reactor heritage and design studies remain distinct from surface power delivery; coupons and small samples are not treated as qualified structures. |
| P4-03 | P4 resources and energy | Additive manufacturing, microgravity production, autonomous assembly, and canceled flight demonstrations | 9 | 10 | 0 | Imported feedstock, ground tests, partial prints, quality analysis, and preflight program conclusions are recorded without implying local industrial capacity. |
| P5-01 | P5 robotics and autonomy | Onboard navigation, autonomous planning, planetary mobility, and terminal guidance | 16 | 10 | 0 | Six JPL pages returned 403 and one NTRS PDF exceeded 5 MiB; equivalent NASA/NTRS HTML records were used without bypass. Local autonomy remains distinct from Earth-selected goals. |
| P5-02 | P5 robotics and autonomy | Remote robotic servicing, free-flying assistants, and autonomous spacecraft caretaking | 8 | 10 | 0 | Ground teleoperation, diagnostics, concurrent robots, simulations, and onboard autonomous inspection are explicitly separated from physical autonomous repair. |
| P5-03 | P5 robotics and autonomy | Autonomous rendezvous, science targeting, cooperative robots, excavation simulation, and navigation beacons | 10 | 10 | 0 | One JPL CADRE page returned 403 and was replaced by NASA Robotics; ground, simulation, sensor, and off-Earth results retain separate boundaries. |
| P6-01 | P6 economics and governance | Commercial launch licensing, cargo and crew contracts, certification, and licensed cadence | 8 | 10 | 0 | Laws, development agreements, demonstrations, recurring services, contract ceilings, certification, and aggregate operation counts remain distinct. |
| P6-02 | P6 economics and governance | Multilateral treaties, national resource laws, sustainability guidance, and Artemis cooperation | 7 | 10 | 0 | Congress.gov returned 403 and was replaced by GovInfo; binding scope, national jurisdiction, voluntary guidance, and nonbinding accords are explicit. |
| P6-03 | P6 economics and governance | Launch risk sharing, interoperability standards, commercial destination funding, pricing, and lunar delivery | 10 | 10 | 0 | Both PDFs remained below 1.3 MiB; plans, prices, awards, standards, and one-off missions are not treated as operational markets. |
| P6-04 | P6 economics and governance | Commercial cargo, lunar delivery, Gateway logistics, and human lander procurement progression | 8 | 10 | 0 | Eight official NASA records cover contracts, demonstrations, operational cargo, provider pools, and lander development while preserving award-versus-operation boundaries. |
| P6-05 | P6 economics and governance | International operating frameworks, repeated private missions, infrastructure retirement, and safety setbacks | 10 | 10 | 0 | Ten official NASA records add governance and negative evidence; agreements, repeat visits, procurements, cancellation, and certification setbacks remain distinct from commissioned settlement capability. |

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
| NASA-NTRS-PDF | NASA-hosted technical report | Excluded from batch | The Deep Impact autonomy report exceeded the 5 MiB transient fingerprint limit; a smaller NTRS abstract that directly states the event was used. |
| UNOOSA | United Nations space-law secretariat | Accepted, Tier 1 agency | Official treaty status and sustainability-guideline records directly establish dates, scope, and legal character. |
| GOVINFO | U.S. Government Publishing Office | Accepted, Tier 1 agency | Official public-law metadata replaced a Congress.gov page that rejected the fingerprint client. |
| LSA | Luxembourg Space Agency | Accepted, Tier 1 agency | Official national legal-framework guidance records the space-resources law and its effective date. |
| CONGRESS-WEB | U.S. Congress bill page | Excluded from batch | The transient fingerprint request returned HTTP 403; GovInfo supplied equivalent official public-law metadata without access-control bypass. |

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
| 2026-07-13 | P3 batch 1 source review and transient fingerprinting | 10 candidates, 9 accepted URLs, each response below 0.4 MiB, no source bytes retained |
| 2026-07-13 | P3 batch 1 final fact review | Surface, orbital, analog, scale, pressure, and occupancy boundaries confirmed |
| 2026-07-13 | P3 batch 2 source review and transient fingerprinting | 10 candidates, 9 accepted URLs, each response below 0.3 MiB, no source bytes retained |
| 2026-07-13 | P3 batch 2 final fact review | Generator/grid and direct-link/relay/network boundaries confirmed |
| 2026-07-13 | P3 batch 3 source review and transient fingerprinting | 10 candidates, 8 accepted URLs, largest response 0.76 MiB, no source bytes retained |
| 2026-07-13 | P3 batch 3 final fact review | Thermal fault, dust control, analog/off-Earth, and 26-month integration boundaries confirmed |
| 2026-07-13 | P3 completion corpus validation | 42/42 focused tests; 100 READY records, 0 rejected, 0 errors |
| 2026-07-13 | P4 batch 1 source review and transient fingerprinting | 10 candidates, 10 accepted URLs, each response below 0.4 MiB, no source bytes retained |
| 2026-07-13 | P4 batch 1 final fact review | Detection/extraction, analog/flight, scale, cancellation, and limited-operation boundaries confirmed |
| 2026-07-13 | P4 batch 2 source review and transient fingerprinting | 10 candidates, 9 accepted URLs, each response below 0.4 MiB, no source bytes retained |
| 2026-07-13 | P4 batch 2 final fact review | Reactor heritage/concept and sample/structure qualification boundaries confirmed |
| 2026-07-13 | P4 batch 3 source review and transient fingerprinting | 10 candidates, 9 accepted URLs, each response below 0.4 MiB, no source bytes retained |
| 2026-07-13 | P4 batch 3 final fact review | Imported/local feedstock, ground/orbit, partial/complete, and length/quality boundaries confirmed |
| 2026-07-13 | P4 completion corpus validation | 42/42 focused tests; 130 READY records, 0 rejected, 0 errors |
| 2026-07-13 | P5 batch 1 source review and transient fingerprinting | 10 candidates, 9 accepted URLs, six 403 pages and one oversized PDF replaced, no source bytes retained |
| 2026-07-13 | P5 batch 1 final fact review | Onboard/pre-programmed, local/mission, and Earth-team/spacecraft recovery boundaries confirmed |
| 2026-07-13 | P5 batch 2 source review and transient fingerprinting | 10 candidates, 8 accepted URLs, each response below 0.4 MiB, no source bytes retained |
| 2026-07-13 | P5 batch 2 final fact review | Remote control, diagnostic, simulation, inspection, and physical repair boundaries confirmed |
| 2026-07-13 | P5 batch 3 source review and transient fingerprinting | 10 candidates, 9 accepted URLs, one 403 page replaced, no source bytes retained |
| 2026-07-13 | P5 batch 3 final fact review | Ground/flight, sensor/control, targeting/mission planning, and simulation/operation boundaries confirmed |
| 2026-07-13 | P5 completion corpus validation | 42/42 focused tests; 160 READY records, 0 rejected, 0 errors |
| 2026-07-13 | P6 batch 1 source review and transient fingerprinting | 10 candidates, 8 accepted URLs, each response below 0.3 MiB, no source bytes retained |
| 2026-07-13 | P6 batch 1 final fact review | Law/agreement, demo/service, ceiling/revenue, certification/market, and cadence/settlement boundaries confirmed |
| 2026-07-13 | P6 batch 2 source review and transient fingerprinting | 10 candidates, 6 accepted URLs, one 403 page replaced, no source bytes retained |
| 2026-07-13 | P6 batch 2 final fact review | Treaty-party, national-jurisdiction, voluntary-guideline, and nonbinding-accord boundaries confirmed |
| 2026-07-13 | P6 batch 3 source review and transient fingerprinting | 10 candidates, 10 accepted URLs, largest response 1.3 MiB, no source bytes retained |
| 2026-07-13 | P6 batch 3 final fact review | Insurance scope, standards/adoption, policy/demand, award/operation, and mission/ecosystem boundaries confirmed |
| 2026-07-13 | P6 completion corpus validation | 42/42 focused tests; 190 READY records, 0 rejected, 0 errors; 180–250 target met |
| 2026-07-13 | Exact corpus-count contract RED test | Expected 210 candidates and observed the intended failure at the prior 190-record state |
| 2026-07-13 | P6 batch 4 source review and transient fingerprinting | 10 candidates, 8 accepted NASA URLs, every response below 0.29 MiB, no source bytes retained |
| 2026-07-13 | P6 batch 4 final fact review | Contract, demonstration, operational-service, provider-pool, and development-award boundaries confirmed |
| 2026-07-13 | P6 batch 5 source review and transient fingerprinting | 10 candidates, 10 accepted NASA URLs, every response below 0.29 MiB, no source bytes retained |
| 2026-07-13 | P6 batch 5 final fact review | Governance, contribution, repeat-mission, retirement, cancellation, and crew-certification boundaries confirmed |
| 2026-07-13 | Exact 210-candidate corpus validation | 42/42 focused tests; 210 READY records, 0 discovered, 0 rejected, 0 errors; 5 catalog sources |
| 2026-07-13 | Full backend regression after exact-count corpus completion | 242 tests, 0 failures, 0 errors, 0 skipped |
