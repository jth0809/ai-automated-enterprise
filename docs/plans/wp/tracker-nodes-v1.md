# Tracker Capability Nodes v1.0

Version: nodes-v1.0
Count: 35
Pillars: 6
Integration nodes: exactly one per pillar
Rubric version: r2.0

Each node definition contains: code, names, pillar, scale, weight,
inclusion boundary, exclusions, level anchors, integration predicate,
and dependency inputs. Partial demonstrations cannot satisfy an
integration node.

Status: **approved for implementation on 2026-07-13**. The user explicitly
approved the node codes, boundaries, and weights. Activation still requires the
matching Flyway migration and `r2.0` prompt to pass their automated tests.

## 1. Registry-wide rules

- Level 0 means no accepted public evidence. Levels 1–9 use the project TRL or
  EGL scale; the node-specific anchors below override a generic analogy when
  the two conflict.
- A node receives the highest level directly demonstrated for that node, not a
  level inferred from a plan, schedule, funding award, neighboring node, or
  component maximum.
- **Graded partial credit (restored from nodes-v0.1).** Partial, sub-scale, or
  single-element demonstrations of a node's critical function receive
  graded credit at a capped lower level — normally L3–L5 per the node anchors —
  rather than zero. A partial demonstration never reaches the node's
  full-capability anchors (L6+ first operational demonstration, L8+ qualified
  operation). A boundary "exclusion"
  removes a milestone from the node's *full-capability* credit; it does not
  discard the intermediate progress the milestone genuinely proves. This keeps
  an all-or-nothing boundary from erasing real technology maturation (for
  example, first-stage-only reuse toward fully reusable launch).
- **Integration nodes also receive graded partial credit.** An integration
  node's own L3/L5 anchors reward partial integration — an end-to-end
  architecture with interface proof (L3), or a crew-loaded analog, sub-scale, or
  shorter off-Earth integrated demonstration (L5) — so a genuine partial
  integration is scored at its capped anchor, not held at L0. Only L8+ requires
  the full pillar conjunction operating through the qualifying 26-month
  settlement campaign. Per-node anchors set each cap: a one-way robotic campaign
  that joins launch, cruise, and landing caps at L3, while a crew-loaded
  full-scale or continuous off-Earth integrated demonstration caps at L5.
- TRL 8–9 and EGL 8–9 advances always require human review. Finish-line use
  additionally requires `OFFICIAL`-or-higher verification.
- An element node has no incoming `capability_edge` in v1.0. Every integration
  node has mandatory AND inputs from all element nodes in its pillar, but the
  edges only cap effective readiness; they never create evidence or raise the
  integration node's observed level.
- Integration level 8 requires the named system to operate as one system in an
  operational settlement context. Evidence from separate actors or missions
  cannot be assembled into an integration demonstration.
- The finish line remains a global conjunction: all core element nodes and all
  six integration nodes must be level 8 or higher, with the state sustained for
  12 months and finally approved by a human reviewer.
- `P2-SURVIVAL-INTEGRATION` is the direct observation node for at least 26
  continuous months with no material resupply from Earth, normal operations,
  and maintained crew health. Pre-positioned low-mass/high-complexity stock is
  allowed, but it cannot replace operation of the registered bulk life-support,
  food, and recovery loops. Delivery of information is allowed; arrival of any
  Earth-origin material resets the observation window.
- The other five integration nodes may reach level 8 only when their integrated
  function is operationally demonstrated in the same settlement campaign that
  can support the 26-month observation. This semantic rule does not add
  cross-pillar V6 edges; the global finish-line state check supplies that
  conjunction.

### Shared anchor vocabulary

| Scale | Level 3 | Level 5 | Level 8 | Level 9 |
|---|---|---|---|---|
| TRL | Analytical and experimental proof of the node's critical function. | Integrated prototype in a relevant environment, still below full operational scale or duration. | Complete node system qualified and operated in the intended settlement mission context. | Repeated, sustained, routine operation with demonstrated reliability and maintainability. |
| EGL | Formal concept, draft instrument, or bounded pilot with committed participants. | Binding or revenue-producing mechanism operating repeatedly for one bounded program or market. | Bankable, enforceable, and resilient mechanism used across normal settlement operations. | Mature, interoperable ecosystem with stable competition, compliance, risk transfer, and continuity. |

## 2. Weight and count control

| Pillar | Element nodes | Integration node | Weight sum |
|---|---:|---:|---:|
| P1 Transport and propulsion | 7 | 1 | 1.00 |
| P2 Life support and human health | 6 | 1 | 1.00 |
| P3 Habitat and infrastructure | 5 | 1 | 1.00 |
| P4 Resources and energy | 4 | 1 | 1.00 |
| P5 Autonomous systems | 3 | 1 | 1.00 |
| P6 Economy and governance | 4 | 1 | 1.00 |
| **Total** | **29** | **6** | **6.00** |

## 3. Pillar 1 — Transport and propulsion

### P1-REUSE-LV — 완전 재사용 발사체 / Fully reusable launch vehicle

- **Pillar / scale / weight:** P1 / TRL / `0.18`.
- **Inclusion boundary:** An orbital-class launch architecture that recovers and
  reflies every mission-critical flight stage and crew/cargo spacecraft,
  including the orbital vehicle or upper stage, with no planned expendable
  major stage in the qualified architecture.
- **Exclusions from full-capability (L6+) credit:** expendable upper stages as
  the qualified architecture; suborbital vehicles; refurbishment claims without a
  demonstrated reflight. First-stage-only recovery earns **capped partial credit
  (≤L5)** toward this node, not full-vehicle-reuse credit.
- **Level anchors:** L3 validated full-vehicle reuse architecture; L5 integrated
  stage prototypes reflown in relevant flight environments (partial-stack reuse
  caps here); L8 operational payload or crew missions with all designated stages
  recovered and reflown; L9 routine high-cadence full-stack reuse across multiple
  missions.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P1-TRANSPORT-INTEGRATION`.

### P1-ORBIT-REFUEL — 궤도 추진제 이송·급유 / In-space propellant transfer and refueling

- **Pillar / scale / weight:** P1 / TRL / `0.16`.
- **Inclusion boundary:** Controlled transfer, storage, and receipt of mission
  propellant between separate spacecraft or depot interfaces in space.
- **Exclusions:** Ground loading; transfer between tanks inside one vehicle;
  docking without propellant transfer; fluid experiments that do not close the
  acquisition, transfer, and receiving loop.
- **Level anchors:** L3 critical fluid-management proof; L5 relevant-environment
  integrated transfer prototype; L8 operational mission refueling at useful
  quantity and quality; L9 routine multi-client depot or tanker service.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P1-TRANSPORT-INTEGRATION`.

### P1-DEEP-PROP — 심우주 추진 (NTP/전기추진) / Deep-space propulsion

- **Pillar / scale / weight:** P1 / TRL / `0.12`.
- **Inclusion boundary:** Nuclear thermal, high-power electric, or comparably
  capable propulsion used to move crew or settlement-scale cargo beyond the
  near-Earth system.
- **Exclusions:** Chemical launch propulsion already covered by other transport
  nodes; low-power station keeping; laboratory thrusters without a mission-scale
  power, thermal, and lifetime chain.
- **Level anchors:** L3 validated propulsion cycle and critical component tests;
  L5 integrated thruster/reactor-power prototype in relevant vacuum and thermal
  conditions; L8 operational deep-space cargo or crew mission; L9 repeated
  mission service with demonstrated lifetime and turnaround.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P1-TRANSPORT-INTEGRATION`.

### P1-EDL-HEAVY — 대형(10t+) 화물 행성 진입·강하·착륙 / Heavy planetary EDL (10 t+)

- **Pillar / scale / weight:** P1 / TRL / `0.14`.
- **Inclusion boundary:** Controlled atmospheric entry, descent, and landing of
  at least 10 metric tonnes of usable cargo on a non-Earth destination.
- **Exclusions:** Earth return; airless-body descent without the applicable EDL
  chain; landings below 10 t; projected payload capacity without a landing.
- **Level anchors:** L3 full-scale analyses with critical decelerator/propulsion
  proof; L5 subscale integrated relevant-environment landing; L8 successful
  operational landing of 10 t or more; L9 repeated accurate heavy landings with
  reliable cargo handoff.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P1-TRANSPORT-INTEGRATION`.

### P1-SURFACE-ASCENT — 행성 표면 이륙·귀환 / Planetary surface ascent and return

- **Pillar / scale / weight:** P1 / TRL / `0.10`.
- **Inclusion boundary:** Launch from a non-Earth surface with delivery of crew
  or settlement-scale cargo to the required rendezvous or return trajectory.
- **Exclusions:** Earth launch; brief hops; ascent engines tested without the
  vehicle, guidance, and rendezvous chain; descent-only missions.
- **Level anchors:** L3 integrated ascent architecture and critical engine proof;
  L5 relevant-environment vehicle prototype; L8 operational crew/cargo ascent
  and rendezvous or return; L9 repeated missions with safe turnaround.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P1-TRANSPORT-INTEGRATION`.

### P1-CREW-SAFE — 유인 수송 안전·비상 탈출 / Crewed transport safety and emergency recovery

- **Pillar / scale / weight:** P1 / TRL / `0.12`.
- **Inclusion boundary:** End-to-end detection, abort, refuge, rescue, and crew
  recovery capability for credible failures during settlement transport.
- **Exclusions:** Generic vehicle reliability without crew escape, rescue, or
  loss-of-crew evidence; uncrewed abort simulations without a qualified system;
  medical treatment after recovery.
- **Level anchors:** L3 validated hazard and abort concepts with critical tests;
  L5 integrated abort/rescue prototype in representative flight conditions; L8
  qualified protection throughout an operational crew mission; L9 repeated
  crew service with exercised contingency and recovery operations.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P1-TRANSPORT-INTEGRATION`.

### P1-ORBIT-LOGISTICS — 궤도 물류·표준화 화물 취급 / Orbital logistics and standardized cargo handling

- **Pillar / scale / weight:** P1 / TRL / `0.08`.
- **Inclusion boundary:** Reusable interfaces and operations for rendezvous,
  docking, transfer, storage, inventory, and handoff of settlement cargo in
  orbit or cislunar space.
- **Exclusions:** Propellant transfer itself (`P1-ORBIT-REFUEL`); launch-market
  economics (`P6-LAUNCH-MARKET`); one-off docking without cargo handling;
  Earth-only warehouse automation.
- **Level anchors:** L3 validated interface and logistics workflow; L5 integrated
  cargo-handling prototype in a relevant environment; L8 operational multi-leg
  cargo transfer with accountable custody and handoff; L9 routine interoperable
  logistics service across vehicles or operators.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P1-TRANSPORT-INTEGRATION`.

### P1-TRANSPORT-INTEGRATION — 지구궤도-행성표면 통합 수송 / Integrated Earth-orbit-to-surface transport

- **Pillar / scale / weight:** P1 / TRL / `0.10`.
- **Inclusion boundary:** One accountable transport campaign joins launch/reuse,
  orbital logistics, refueling, deep-space propulsion, landing, surface ascent,
  and crew safety into a settlement supply and crew route. Cargo and crew legs
  may use different vehicles, but every registered element must be exercised
  inside the campaign's operational architecture.
- **Exclusions from full-route (L5+) credit:** A paper architecture; disconnected
  demonstrations by different systems; a mission missing required crew-safety or
  return functions. A one-way robotic mission that integrates launch, cruise, and
  landing as one campaign earns capped partial credit (L3), not full-route credit.
- **Level anchors:** L3 end-to-end architecture with interface proof; L5 an
  integrated uncrewed or subscale mission spanning major legs; L8 operational
  settlement transport used within the qualifying 26-month campaign; L9
  repeated safe, scheduled, multi-mission service.
- **Integration predicate:** All seven P1 elements must operate through governed
  interfaces as one traceable campaign; component maxima or unrelated missions
  cannot be substituted for the observed integrated level.
- **Dependency inputs:** `P1-REUSE-LV`, `P1-ORBIT-REFUEL`, `P1-DEEP-PROP`,
  `P1-EDL-HEAVY`, `P1-SURFACE-ASCENT`, `P1-CREW-SAFE`,
  `P1-ORBIT-LOGISTICS`.

## 4. Pillar 2 — Life support and human health

### P2-ECLSS — 폐쇄 순환 생명 유지 (물·공기) / Closed-loop life support

- **Pillar / scale / weight:** P2 / TRL / `0.24`.
- **Inclusion boundary:** Regeneration and control of breathable atmosphere and
  potable/process water for a crewed off-Earth habitat.
- **Exclusions:** Open-loop consumables alone; solid/organic waste recovery
  assigned to `P2-WASTE-CYCLE`; food production; short tests without crew-load
  closure data.
- **Level anchors:** L3 critical regeneration processes proven; L5 integrated
  prototype under representative crew loads; L8 complete operational closed
  loop in an off-Earth habitat; L9 sustained high-closure operation with routine
  maintenance and fault recovery.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P2-SURVIVAL-INTEGRATION`.

### P2-FOOD — 우주·행성 표면 식량 생산 / Space and surface food production

- **Pillar / scale / weight:** P2 / TRL / `0.16`.
- **Inclusion boundary:** Safe, nutritionally useful food production and
  processing under spacecraft or planetary-habitat constraints.
- **Exclusions:** Plant growth with no edible yield; stored food logistics;
  terrestrial controlled-environment agriculture without space constraints;
  nutritional studies without production.
- **Level anchors:** L3 edible production process proof; L5 repeated integrated
  crop/bioprocess prototype with representative resources; L8 operationally
  supplies a material fraction of crew nutrition; L9 sustained diverse output
  with stable yield, safety, and recycling.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P2-SURVIVAL-INTEGRATION`.

### P2-RAD — 방사선 방호 (심우주·표면) / Deep-space and surface radiation protection

- **Pillar / scale / weight:** P2 / TRL / `0.12`.
- **Inclusion boundary:** Measurement, forecasting, shielding, sheltering, or
  active mitigation that keeps crew exposure within an approved mission risk
  envelope beyond low Earth orbit.
- **Exclusions:** Radiation science without a protection system; terrestrial
  shielding; medical treatment after exposure; generic structural mass with no
  quantified protection function.
- **Level anchors:** L3 protection concept and critical material/sensor proof;
  L5 integrated protection prototype in representative radiation conditions;
  L8 qualified protection used for an operational long-duration crew mission;
  L9 repeated mission operation with validated cumulative-dose control.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P2-SURVIVAL-INTEGRATION`.

### P2-MED — 저중력 장기 체류 의학 / Long-duration partial-gravity medicine

- **Pillar / scale / weight:** P2 / TRL / `0.12`.
- **Inclusion boundary:** Evidence and countermeasures for chronic physiological
  and behavioral risks of long-duration microgravity or partial gravity.
- **Exclusions:** Independent diagnosis/treatment operations assigned to
  `P2-HEALTH-AUTONOMY`; short-duration motion sickness; generic terrestrial
  medicine; exercise hardware without health outcome evidence.
- **Level anchors:** L3 causal risk/countermeasure proof; L5 integrated regimen
  validated in a relevant analog or flight population; L8 operational regimen
  maintains fitness and function through settlement-duration exposure; L9
  repeated long-duration outcomes with controlled chronic risk.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P2-SURVIVAL-INTEGRATION`.

### P2-WASTE-CYCLE — 고형·유기 폐기물 자원 순환 / Solid and organic waste resource cycling

- **Pillar / scale / weight:** P2 / TRL / `0.12`.
- **Inclusion boundary:** Safe collection, stabilization, processing, and reuse
  of solid and organic crew/habitat waste into useful water, nutrients,
  feedstock, or other resources.
- **Exclusions:** Air and water regeneration already in `P2-ECLSS`; disposal or
  storage without recovery; terrestrial municipal systems; laboratory yields
  that omit contamination and crew-safety controls.
- **Level anchors:** L3 critical conversion proof; L5 integrated prototype under
  representative mixed waste and crew loads; L8 operational closed handling and
  useful resource recovery in habitat service; L9 sustained high-recovery,
  sanitary operation with maintainable residues.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P2-SURVIVAL-INTEGRATION`.

### P2-HEALTH-AUTONOMY — 지구 지원 없는 임상·의료 운영 / Earth-independent clinical operations

- **Pillar / scale / weight:** P2 / TRL / `0.10`.
- **Inclusion boundary:** Crew and onboard systems can diagnose, triage, treat,
  and manage credible acute and chronic conditions despite communication delay
  or loss of real-time Earth support.
- **Exclusions:** Biomedical knowledge assigned to `P2-MED`; telemedicine that
  requires continuous Earth specialists; a diagnostic algorithm without
  treatment capability; evacuation as the primary response.
- **Level anchors:** L3 integrated diagnostic/treatment workflow proof; L5
  representative autonomous clinical simulations with trained crew and tools;
  L8 operational care capability for the mission risk set without real-time
  Earth control; L9 repeated independent care with validated outcomes and
  replenishment/sterility management.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P2-SURVIVAL-INTEGRATION`.

### P2-SURVIVAL-INTEGRATION — 26개월 무보급 생존·건강 유지 / 26-month no-resupply crew survival

- **Pillar / scale / weight:** P2 / TRL / `0.14`.
- **Inclusion boundary:** A continuously crewed off-Earth settlement maintains
  normal operations and crew health while no material from Earth arrives for at
  least 26 continuous months, with its bulk life-support, food, and resource
  recovery loops operating rather than being replaced by pre-positioned bulk
  consumables.
- **Exclusions:** Uncrewed tests; Earth analogs as L8 evidence; reduced emergency
  survival; a closed-loop subsystem test; a period interrupted by any material
  delivery; plans or inventory projections.
- **Level anchors:** L3 integrated survival architecture and mass-balance proof;
  L5 crew-loaded Earth analog or shorter off-Earth integrated demonstration; L8
  observed 26-month qualifying period with normal operations and health; L9 a
  second qualifying period or at least 52 months demonstrating missed-window
  resilience.
- **Integration predicate:** All six P2 element capabilities operate together
  for the same crew and interval. The 26-month clock and no-material-resupply
  condition are indivisible; component levels cannot satisfy it.
- **Dependency inputs:** `P2-ECLSS`, `P2-FOOD`, `P2-RAD`, `P2-MED`,
  `P2-WASTE-CYCLE`, `P2-HEALTH-AUTONOMY`.

## 5. Pillar 3 — Habitat and infrastructure

### P3-CONSTRUCT — 표면 거주지 건설 (현지 재료 포함) / Surface habitat construction

- **Pillar / scale / weight:** P3 / TRL / `0.23`.
- **Inclusion boundary:** Construction, assembly, sealing, and commissioning of
  durable crew habitat structures on a non-Earth surface, including use of
  local material where applicable.
- **Exclusions:** Production of parts/feedstock assigned to
  `P4-MANUFACTURING`; robotic capability assigned to `P5-AUTOCON`; temporary
  lander cabins; material coupons without a habitable structure.
- **Level anchors:** L3 structural process and habitat concept proof; L5
  integrated full-scale section in a relevant environment; L8 commissioned
  operational surface habitat; L9 repeated expansion/repair with stable
  pressure, structure, and habitability.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P3-HABITAT-INTEGRATION`.

### P3-POWER — 표면 전력 계통 (발전·저장·배전) / Surface power system

- **Pillar / scale / weight:** P3 / TRL / `0.22`.
- **Inclusion boundary:** Settlement-scale generation interfaces, storage,
  distribution, protection, and load management operating as a surface grid.
- **Exclusions:** Reactor or generator core technology alone
  (`P4-NUKE` where applicable); isolated device power; mission concepts without
  storage/distribution; terrestrial microgrids.
- **Level anchors:** L3 grid architecture and critical conversion/storage proof;
  L5 integrated representative microgrid under environmental/load cycles; L8
  operational settlement grid across normal and contingency modes; L9 sustained
  redundant service with expansion and black-start recovery.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P3-HABITAT-INTEGRATION`.

### P3-COMMS — 행성 간·표면 통신망 / Interplanetary and surface communications network

- **Pillar / scale / weight:** P3 / TRL / `0.15`.
- **Inclusion boundary:** Delay-tolerant, navigable communications spanning the
  settlement, local assets, relay infrastructure, and Earth links.
- **Exclusions:** A single direct-to-Earth radio; generic satellite broadband;
  astronomy links; network designs without operational routing and outage
  handling.
- **Level anchors:** L3 protocol/network proof; L5 integrated representative
  surface-relay-Earth network; L8 operational settlement communications with
  delay/outage handling; L9 routine interoperable service with redundancy and
  autonomous network management.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P3-HABITAT-INTEGRATION`.

### P3-THERMAL — 표면 열제어·환경 차폐 / Surface thermal control and environmental shielding

- **Pillar / scale / weight:** P3 / TRL / `0.12`.
- **Inclusion boundary:** Habitat-scale thermal rejection, insulation, heat
  transport, and non-radiation environmental protection across destination
  cycles and credible faults.
- **Exclusions:** Crew radiation protection (`P2-RAD`); dust control
  (`P3-DUST`); component thermal testing without habitat heat balance; generic
  structural insulation.
- **Level anchors:** L3 critical thermal/environment control proof; L5 integrated
  habitat-scale prototype under representative cycles; L8 operational habitat
  maintains limits through seasonal and fault cases; L9 sustained efficient
  operation with maintainable/redundant loops.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P3-HABITAT-INTEGRATION`.

### P3-DUST — 먼지·레골리스 오염 제어 / Dust and regolith contamination control

- **Pillar / scale / weight:** P3 / TRL / `0.10`.
- **Inclusion boundary:** Prevention, removal, containment, and monitoring of
  abrasive or toxic extraterrestrial dust across suits, airlocks, mechanisms,
  habitats, and exposed infrastructure.
- **Exclusions:** Terrestrial clean-room control; material abrasion tests without
  a control system; generic filtration already counted as atmosphere closure;
  thermal or radiation shielding.
- **Level anchors:** L3 control mechanism proof with representative simulant; L5
  integrated airlock/equipment prototype in relevant conditions; L8 operational
  settlement contamination control across crew and infrastructure interfaces;
  L9 sustained low-exposure operation with routine cleaning and maintenance.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P3-HABITAT-INTEGRATION`.

### P3-HABITAT-INTEGRATION — 26개월 표면 거주 인프라 통합 운용 / Integrated surface habitation infrastructure

- **Pillar / scale / weight:** P3 / TRL / `0.18`.
- **Inclusion boundary:** Constructed habitat, grid, communications, thermal
  control, and dust control operate together as the inhabited settlement
  infrastructure.
- **Exclusions:** An unoccupied analog; independent subsystem tests; a temporary
  sortie habitat; nominal operation without credible contingency modes.
- **Level anchors:** L3 integrated habitat architecture and interface proof; L5
  crew-loaded full-scale analog or shorter off-Earth integrated operation; L8
  infrastructure supports normal habitation throughout the qualifying 26-month
  campaign; L9 repeated/expanded operation with fault recovery and maintainable
  service continuity.
- **Integration predicate:** All five P3 element nodes serve the same occupied
  settlement and operating interval; component demonstrations cannot be pooled.
- **Dependency inputs:** `P3-CONSTRUCT`, `P3-POWER`, `P3-COMMS`, `P3-THERMAL`,
  `P3-DUST`.

## 6. Pillar 4 — Resources and energy

### P4-ISRU-PROP — ISRU: 추진제·물·산소 현지 생산 / Local propellant, water, and oxygen production

- **Pillar / scale / weight:** P4 / TRL / `0.30`.
- **Inclusion boundary:** Extraction and processing of extraterrestrial feedstock
  into mission-usable propellant, water, oxygen, or their required precursors.
- **Exclusions:** Resource detection alone; trace laboratory production;
  terrestrial mining; parts manufacturing (`P4-MANUFACTURING`); storage without
  local production.
- **Level anchors:** L3 critical extraction/conversion proof; L5 integrated
  prototype with representative feedstock/environment; L8 operationally useful
  product at settlement scale and quality; L9 sustained production with stable
  yield, maintenance, and inventory contribution.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P4-RESOURCE-INTEGRATION`.

### P4-NUKE — 우주용 소형 원자로/핵융합 / Space-rated fission or fusion power

- **Pillar / scale / weight:** P4 / TRL / `0.22`.
- **Inclusion boundary:** Fission or fusion power systems qualified for space
  transport and off-Earth surface or in-space settlement service.
- **Exclusions:** Terrestrial reactors; radioisotope units below settlement power
  function; paper concepts; component tests that omit integrated conversion,
  rejection, control, and safety.
- **Level anchors:** L3 critical nuclear and conversion proof; L5 integrated
  ground prototype under representative thermal/load conditions; L8 qualified
  operational power in the intended off-Earth mission; L9 repeated or sustained
  service with maintainable safety and load following.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P4-RESOURCE-INTEGRATION`.

### P4-MATERIALS — 극한환경 구조 소재 / Extreme-environment structural materials

- **Pillar / scale / weight:** P4 / TRL / `0.14`.
- **Inclusion boundary:** Materials and joined structures qualified for the
  thermal, radiation, vacuum, dust, fatigue, and lifetime conditions of
  settlement infrastructure.
- **Exclusions:** Material discovery without structural validation; generic
  terrestrial alloys; manufacturing process maturity
  (`P4-MANUFACTURING`); completed habitat assembly (`P3-CONSTRUCT`).
- **Level anchors:** L3 coupon and degradation mechanism proof; L5 joined
  subassembly under combined relevant environments; L8 material system qualified
  and used in operational settlement hardware; L9 sustained field life with
  repair, inspection, and aging evidence.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P4-RESOURCE-INTEGRATION`.

### P4-MANUFACTURING — 현지 제조·예비품 생산 / Local manufacturing and spare-part production

- **Pillar / scale / weight:** P4 / TRL / `0.16`.
- **Inclusion boundary:** Production and qualification of usable tools, spares,
  or structural parts from local or recycled feedstock in the destination
  environment.
- **Exclusions:** Printing with entirely Earth-supplied feedstock as the final
  capability; materials research (`P4-MATERIALS`); habitat assembly
  (`P3-CONSTRUCT`); repair planning without physical production.
- **Level anchors:** L3 feedstock-to-part process proof; L5 representative
  equipment produces and qualifies mission-like parts; L8 operational local or
  recycled feedstock produces flight/habitat-usable spares; L9 sustained diverse
  production closes recurring maintenance demand.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P4-RESOURCE-INTEGRATION`.

### P4-RESOURCE-INTEGRATION — ISRU-전력-저장-제조 통합 운용 / Integrated resource and production operations

- **Pillar / scale / weight:** P4 / TRL / `0.18`.
- **Inclusion boundary:** Local extraction, energy conversion, material
  qualification, storage, and manufacturing exchange measured resources as one
  operating settlement production chain.
- **Exclusions:** Independent pilot plants; imported feedstock passed off as
  local closure; energy generation without production loads; a mass-balance
  model without physical outputs.
- **Level anchors:** L3 end-to-end mass/energy architecture and interface proof;
  L5 integrated pilot from representative feedstock to qualified output; L8
  production chain materially supports normal operations during the qualifying
  26-month campaign; L9 sustained production covers multiple recurring resource
  and spare demands with recoverable faults.
- **Integration predicate:** All four P4 element nodes participate in one audited
  mass/energy chain. P3 grid/storage service is observed in the global campaign
  but is not duplicated as a V6 cross-pillar edge.
- **Dependency inputs:** `P4-ISRU-PROP`, `P4-NUKE`, `P4-MATERIALS`,
  `P4-MANUFACTURING`.

## 7. Pillar 5 — Autonomous systems

### P5-AUTOCON — 무인 선행 건설·조립 로봇 / Robotic precursor construction and assembly

- **Pillar / scale / weight:** P5 / TRL / `0.28`.
- **Inclusion boundary:** Robotic systems construct, assemble, connect, or
  commission settlement assets before or with minimal local crew intervention.
- **Exclusions:** Earth-only robotics; manually teleoperated demos with no delay
  tolerance; inspection/repair assigned to `P5-MAINTENANCE`; planning software
  without physical work.
- **Level anchors:** L3 critical manipulation/construction proof; L5 integrated
  robot completes representative work under delay and environment constraints;
  L8 operational precursor system commissions settlement infrastructure; L9
  repeated autonomous expansion across varied tasks and faults.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P5-OPS-INTEGRATION`.

### P5-AUTONOMY — 장주기 자율 운영 (항법·정비·이상 대응) / Long-duration autonomous operations

- **Pillar / scale / weight:** P5 / TRL / `0.27`.
- **Inclusion boundary:** Onboard planning, navigation, scheduling, diagnosis,
  and anomaly response sustain operations despite communication delay and
  intermittent Earth contact.
- **Exclusions:** General AI without a space application; advisory analytics
  that require human execution; physical inspection/repair
  (`P5-MAINTENANCE`); short scripted automation.
- **Level anchors:** L3 autonomous decision-loop proof; L5 integrated system
  handles representative scenarios and faults; L8 operational long-duration
  mission runs with bounded Earth supervision; L9 sustained multi-system
  autonomy adapts to novel faults within audited safety limits.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P5-OPS-INTEGRATION`.

### P5-MAINTENANCE — 자율 정비·고장 복구 / Autonomous inspection, maintenance, and repair

- **Pillar / scale / weight:** P5 / TRL / `0.20`.
- **Inclusion boundary:** Robotic or onboard systems physically inspect,
  diagnose, service, repair, or replace settlement hardware with bounded crew
  and Earth support.
- **Exclusions:** Planning/navigation software (`P5-AUTONOMY`); construction of
  new habitat (`P5-AUTOCON`); manual repair as the normal path; fault detection
  without physical restoration.
- **Level anchors:** L3 critical inspection/repair method proof; L5 integrated
  representative fault diagnosis and physical repair; L8 operational system
  restores mission-critical hardware during settlement service; L9 repeated
  preventive and corrective maintenance closes diverse failure classes.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P5-OPS-INTEGRATION`.

### P5-OPS-INTEGRATION — 장기 자율기지 통합 운영 / Integrated long-duration autonomous base operations

- **Pillar / scale / weight:** P5 / TRL / `0.25`.
- **Inclusion boundary:** Construction robotics, operational autonomy, and
  physical maintenance coordinate as one bounded-control system for settlement
  operations under communication delay.
- **Exclusions:** A software-only autonomy demo; unrelated robots operated by
  separate teams; continuous real-time Earth control; nominal operation without
  fault detection and restoration.
- **Level anchors:** L3 integrated autonomy architecture and authority/safety
  proof; L5 representative base analog handles planned work and injected faults;
  L8 system supports normal and contingency operations during the qualifying
  26-month campaign; L9 repeated long-duration operation with audited recovery
  from multiple fault classes.
- **Integration predicate:** All three P5 elements share mission state, authority
  boundaries, and recovery workflows for the same base and interval.
- **Dependency inputs:** `P5-AUTOCON`, `P5-AUTONOMY`, `P5-MAINTENANCE`.

## 8. Pillar 6 — Economy and governance

### P6-LAUNCH-MARKET — 발사 시장·수송 경제성 / Launch market and transport economics

- **Pillar / scale / weight:** P6 / EGL / `0.27`.
- **Inclusion boundary:** Observable availability, competition, cadence, price,
  and recurring demand for transport services that can serve off-Earth
  settlement logistics.
- **Exclusions:** A single subsidized mission; announced prices without sales;
  defense-only launch demand; vehicle TRL; orbital cargo handling
  (`P1-ORBIT-LOGISTICS`).
- **Level anchors:** L3 committed anchor contracts or bounded service pilot; L5
  repeated revenue from one provider and customers; L8 stable bankable pricing,
  capacity, and risk terms supporting normal settlement supply; L9 mature
  competitive transport market with interoperable services and resilient
  capacity.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P6-SETTLEMENT-INTEGRATION`.

### P6-GOV-FRAMEWORK — 우주 자원·거주 국제 규범·법제 / Space resource and settlement governance

- **Pillar / scale / weight:** P6 / EGL / `0.24`.
- **Inclusion boundary:** Binding, implementable rules for jurisdiction,
  registration, safety, resource use, property/contract rights, liability, and
  dispute handling for persistent off-Earth activity.
- **Exclusions:** Nonbinding discussion alone; general space law unrelated to
  settlement operation; a draft with no formal sponsor; corporate policy.
- **Level anchors:** L3 formal draft instrument; L5 adopted and legally effective
  rule for at least one major jurisdiction or agreement; L8 enforceable,
  interoperable framework with operating and dispute-resolution precedent; L9
  broadly observed stable international regime with reliable compliance.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P6-SETTLEMENT-INTEGRATION`.

### P6-FUNDING — 지속 가능 자금 조달·민간 투자 / Durable funding and private investment

- **Pillar / scale / weight:** P6 / EGL / `0.17`.
- **Inclusion boundary:** Repeatable capital formation and operating finance for
  settlement-enabling infrastructure beyond one political or venture cycle.
- **Exclusions:** Nonbinding budget requests; one research grant; valuation or
  fundraising announcements without deployed capital; launch revenue counted
  again as funding maturity.
- **Level anchors:** L3 committed pilot/program capital; L5 repeated financing
  and operating revenue for a bounded infrastructure program; L8 diversified,
  bankable long-duration capital survives normal delays and losses; L9 mature
  refinancing and reinvestment ecosystem independent of exceptional sponsorship.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P6-SETTLEMENT-INTEGRATION`.

### P6-INSURANCE-STD — 보험·표준·금융 인프라 / Insurance, standards, and financial infrastructure

- **Pillar / scale / weight:** P6 / EGL / `0.14`.
- **Inclusion boundary:** Actuarial risk transfer, technical/contract standards,
  certification, payment, escrow, and related financial infrastructure that
  makes settlement activity repeatable across organizations.
- **Exclusions:** Bespoke government indemnity for one mission; voluntary draft
  guidance with no use; terrestrial insurance unrelated to off-Earth risk;
  capital provision already counted in `P6-FUNDING`.
- **Level anchors:** L3 pilot policy, standard, or certification with committed
  participants; L5 repeated use across real missions by one market segment; L8
  priced and enforceable risk transfer plus interoperable standards used in
  normal settlement operations; L9 mature multi-provider coverage, certification,
  settlement, and loss-history feedback.
- **Integration predicate:** Not applicable; element node.
- **Dependency inputs:** None; input to `P6-SETTLEMENT-INTEGRATION`.

### P6-SETTLEMENT-INTEGRATION — 정착 경제·법제·공급망 지속 운용 / Durable settlement economic and governance ecosystem

- **Pillar / scale / weight:** P6 / EGL / `0.18`.
- **Inclusion boundary:** Transport markets, binding governance, durable finance,
  insurance, standards, and payment/contract mechanisms jointly sustain normal
  settlement operations across delay, loss, and political change.
- **Exclusions:** A single state program insulated from market/legal tests;
  separate policies and contracts with no operational use; temporary emergency
  funding; aspirational international principles.
- **Level anchors:** L3 coordinated pilot with draft institutional interfaces; L5
  one bounded settlement program operates under binding rules and recurring
  finance; L8 the ecosystem remains bankable and enforceable throughout the
  qualifying 26-month campaign; L9 multiple operators/jurisdictions sustain
  resilient competition, compliance, risk transfer, and continuity.
- **Integration predicate:** All four P6 element mechanisms apply to the same
  operating settlement and survive at least one material operational stress;
  independent paper instruments cannot be pooled.
- **Dependency inputs:** `P6-LAUNCH-MARKET`, `P6-GOV-FRAMEWORK`, `P6-FUNDING`,
  `P6-INSURANCE-STD`.

## 9. Review checklist

- [x] The 35 codes and Korean names are the intended product vocabulary.
- [x] Each inclusion/exclusion boundary prevents obvious double counting.
- [x] The 10 t threshold for heavy EDL and the full-stack condition for reuse are
  retained.
- [x] The 26-month clock, material-resupply reset rule, normal-operations test,
  and crew-health test are acceptable.
- [x] Each pillar has one integration node and its listed inputs are mandatory.
- [x] Provisional weights reflect product priorities well enough to seed r2.0;
  later AHP calibration may create a new version rather than silently changing
  nodes-v1.0.
- [x] Level 8 anchors are strict enough that partial demonstrations cannot claim
  operational settlement readiness.
