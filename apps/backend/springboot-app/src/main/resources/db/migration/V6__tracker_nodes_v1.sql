-- V6__tracker_nodes_v1.sql
--
-- Approved capability registry migration from nodes-v0.1 (20 rows) to
-- nodes-v1.0 (35 rows). The canonical boundaries and level anchors are in
-- docs/plans/wp/tracker-nodes-v1.md. All retained rows are updated explicitly,
-- all new rows are inserted explicitly, and integration dependencies remain
-- pillar-local mandatory AND inputs (or_group=1).

-- ---------------------------------------------------------------------------
-- Retained 20 nodes: approved weights, version, and auditable boundaries.
-- ---------------------------------------------------------------------------
UPDATE capability_node
   SET weight = 0.1800,
       node_set_version = 'nodes-v1.0',
       description = 'Orbital-class architecture recovers and reflies every mission-critical stage and spacecraft. First-stage-only recovery, expendable upper stages, suborbital vehicles, and unsupported refurbishment claims are excluded.'
 WHERE code = 'P1-REUSE-LV';

UPDATE capability_node
   SET weight = 0.1600,
       node_set_version = 'nodes-v1.0',
       description = 'Controlled storage, transfer, and receipt of mission propellant between separate spacecraft or depot interfaces in space. Ground loading, internal tank transfer, docking alone, and incomplete fluid experiments are excluded.'
 WHERE code = 'P1-ORBIT-REFUEL';

UPDATE capability_node
   SET weight = 0.1200,
       node_set_version = 'nodes-v1.0',
       description = 'Nuclear thermal, high-power electric, or comparable propulsion moves crew or settlement-scale cargo beyond the near-Earth system. Low-power station keeping and laboratory thrusters without the mission power, thermal, and lifetime chain are excluded.'
 WHERE code = 'P1-DEEP-PROP';

UPDATE capability_node
   SET weight = 0.1400,
       node_set_version = 'nodes-v1.0',
       description = 'Controlled atmospheric entry, descent, and landing of at least 10 metric tonnes of usable cargo on a non-Earth destination. Earth return, sub-10-tonne landings, airless descent, and projected capacity without a landing are excluded.'
 WHERE code = 'P1-EDL-HEAVY';

UPDATE capability_node
   SET weight = 0.1000,
       node_set_version = 'nodes-v1.0',
       description = 'Launch from a non-Earth surface delivers crew or settlement-scale cargo to rendezvous or return trajectory. Earth launch, brief hops, engine-only tests, and descent-only missions are excluded.'
 WHERE code = 'P1-SURFACE-ASCENT';

UPDATE capability_node
   SET weight = 0.2400,
       node_set_version = 'nodes-v1.0',
       description = 'Regeneration and control of breathable atmosphere and potable or process water for a crewed off-Earth habitat. Open-loop consumables, solid waste recovery, food production, and short tests without crew-load closure data are excluded.'
 WHERE code = 'P2-ECLSS';

UPDATE capability_node
   SET weight = 0.1600,
       node_set_version = 'nodes-v1.0',
       description = 'Safe, nutritionally useful food production and processing under spacecraft or planetary-habitat constraints. Plant growth without edible yield, stored food, unconstrained terrestrial agriculture, and nutrition studies without production are excluded.'
 WHERE code = 'P2-FOOD';

UPDATE capability_node
   SET weight = 0.1200,
       node_set_version = 'nodes-v1.0',
       description = 'Measurement, forecasting, shielding, sheltering, or active mitigation keeps crew radiation exposure within an approved risk envelope beyond low Earth orbit. Radiation science without protection and unquantified generic structure are excluded.'
 WHERE code = 'P2-RAD';

UPDATE capability_node
   SET weight = 0.1200,
       node_set_version = 'nodes-v1.0',
       description = 'Evidence and countermeasures address chronic physiological and behavioral risks of long-duration microgravity or partial gravity. Independent clinical operations, short-duration sickness, and hardware without health outcomes are excluded.'
 WHERE code = 'P2-MED';

UPDATE capability_node
   SET weight = 0.2300,
       node_set_version = 'nodes-v1.0',
       description = 'Construction, assembly, sealing, and commissioning produce durable crew habitat structures on a non-Earth surface. Parts manufacture, robotic capability alone, temporary lander cabins, and material coupons are excluded.'
 WHERE code = 'P3-CONSTRUCT';

UPDATE capability_node
   SET weight = 0.2200,
       node_set_version = 'nodes-v1.0',
       description = 'Settlement-scale generation interfaces, storage, distribution, protection, and load management operate as a surface grid. Generator-core technology alone, isolated device power, and designs without storage or distribution are excluded.'
 WHERE code = 'P3-POWER';

UPDATE capability_node
   SET weight = 0.1500,
       node_set_version = 'nodes-v1.0',
       description = 'Delay-tolerant navigable communications span settlement assets, local relays, and Earth links with outage handling. A single direct radio, generic broadband, astronomy links, and paper network designs are excluded.'
 WHERE code = 'P3-COMMS';

UPDATE capability_node
   SET weight = 0.3000,
       node_set_version = 'nodes-v1.0',
       description = 'Extraction and processing of extraterrestrial feedstock produces mission-usable propellant, water, oxygen, or required precursors. Detection alone, trace laboratory output, terrestrial mining, manufacturing, and storage without local production are excluded.'
 WHERE code = 'P4-ISRU-PROP';

UPDATE capability_node
   SET weight = 0.2200,
       node_set_version = 'nodes-v1.0',
       description = 'Fission or fusion power systems are qualified for space transport and off-Earth settlement service. Terrestrial reactors, low-power radioisotope units, paper concepts, and component tests omitting conversion, rejection, control, or safety are excluded.'
 WHERE code = 'P4-NUKE';

UPDATE capability_node
   SET weight = 0.1400,
       node_set_version = 'nodes-v1.0',
       description = 'Materials and joined structures are qualified for combined thermal, radiation, vacuum, dust, fatigue, and lifetime conditions. Discovery without structural validation, generic terrestrial alloys, manufacturing maturity, and habitat assembly are excluded.'
 WHERE code = 'P4-MATERIALS';

UPDATE capability_node
   SET weight = 0.2800,
       node_set_version = 'nodes-v1.0',
       description = 'Robotic systems construct, assemble, connect, or commission settlement assets before or with minimal local crew intervention. Earth-only robotics, delay-intolerant teleoperation, maintenance, and planning software without physical work are excluded.'
 WHERE code = 'P5-AUTOCON';

UPDATE capability_node
   SET weight = 0.2700,
       node_set_version = 'nodes-v1.0',
       description = 'Onboard planning, navigation, scheduling, diagnosis, and anomaly response sustain operations despite communication delay and intermittent Earth contact. Advisory analytics, physical repair, and short scripted automation are excluded.'
 WHERE code = 'P5-AUTONOMY';

UPDATE capability_node
   SET weight = 0.2700,
       node_set_version = 'nodes-v1.0',
       description = 'Observable availability, competition, cadence, price, and recurring demand support off-Earth settlement transport. A single subsidized mission, announced prices without sales, defense-only demand, vehicle TRL, and orbital handling are excluded.'
 WHERE code = 'P6-LAUNCH-MARKET';

UPDATE capability_node
   SET weight = 0.2400,
       node_set_version = 'nodes-v1.0',
       description = 'Binding implementable rules cover jurisdiction, registration, safety, resource use, rights, liability, and disputes for persistent off-Earth activity. Nonbinding discussion, unrelated general space law, unsponsored drafts, and corporate policy are excluded.'
 WHERE code = 'P6-GOV-FRAMEWORK';

UPDATE capability_node
   SET weight = 0.1700,
       node_set_version = 'nodes-v1.0',
       description = 'Repeatable capital formation and operating finance support settlement infrastructure beyond one political or venture cycle. Budget requests, one research grant, undeployed fundraising, and launch revenue double counting are excluded.'
 WHERE code = 'P6-FUNDING';

-- ---------------------------------------------------------------------------
-- Nine new element nodes.
-- ---------------------------------------------------------------------------
INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P1-CREW-SAFE', 1, '유인 수송 안전·비상 탈출', 'TRL', 0, 0.1200,
  'N', 'ACTIVE',
  'End-to-end detection, abort, refuge, rescue, and crew recovery address credible settlement-transport failures. Generic reliability, uncrewed simulations without qualification, and post-recovery medical treatment are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P1-ORBIT-LOGISTICS', 1, '궤도 물류·저장·화물 취급', 'TRL', 0, 0.0800,
  'N', 'ACTIVE',
  'Reusable interfaces and operations provide rendezvous, docking, transfer, storage, inventory, and accountable cargo handoff in orbit or cislunar space. Propellant transfer, launch economics, one-off docking, and Earth-only warehousing are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P2-WASTE-CYCLE', 2, '고형·유기 폐기물 자원 순환', 'TRL', 0, 0.1200,
  'N', 'ACTIVE',
  'Safe collection, stabilization, processing, and reuse convert solid and organic habitat waste into useful resources. Air and water regeneration, disposal without recovery, terrestrial municipal systems, and unsafe laboratory yields are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P2-HEALTH-AUTONOMY', 2, '지구 지원 없는 임상·의료 운영', 'TRL', 0, 0.1000,
  'N', 'ACTIVE',
  'Crew and onboard systems diagnose, triage, treat, and manage credible conditions without real-time Earth support. Biomedical knowledge, continuously Earth-dependent telemedicine, diagnosis without treatment, and evacuation as the primary response are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P3-THERMAL', 3, '표면 열제어·환경 차폐', 'TRL', 0, 0.1200,
  'N', 'ACTIVE',
  'Habitat-scale thermal rejection, insulation, heat transport, and non-radiation environmental protection operate across destination cycles and credible faults. Radiation, dust control, component-only tests, and generic insulation are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P3-DUST', 3, '먼지·레골리스 오염 제어', 'TRL', 0, 0.1000,
  'N', 'ACTIVE',
  'Prevention, removal, containment, and monitoring control extraterrestrial dust across suits, airlocks, mechanisms, habitats, and infrastructure. Terrestrial clean rooms, abrasion tests alone, generic filtration, and thermal or radiation shielding are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P4-MANUFACTURING', 4, '현지 제조·예비품 생산', 'TRL', 0, 0.1600,
  'N', 'ACTIVE',
  'Tools, spares, or structural parts are produced and qualified from local or recycled feedstock at the destination. Earth-supplied feedstock as the final capability, materials research, habitat assembly, and repair planning without production are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P5-MAINTENANCE', 5, '자율 정비·고장 복구', 'TRL', 0, 0.2000,
  'N', 'ACTIVE',
  'Robotic or onboard systems physically inspect, diagnose, service, repair, or replace settlement hardware with bounded support. Planning software, new construction, routine manual repair, and fault detection without physical restoration are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P6-INSURANCE-STD', 6, '보험·표준·금융 인프라', 'EGL', 0, 0.1400,
  'N', 'ACTIVE',
  'Actuarial risk transfer, technical and contract standards, certification, payment, and escrow make settlement activity repeatable across organizations. One-off indemnity, unused draft guidance, unrelated terrestrial insurance, and capital provision are excluded.',
  'nodes-v1.0'
);

-- ---------------------------------------------------------------------------
-- Six integration nodes. Their levels require direct evidence of the same
-- system operating as a unit; component maxima never create integration credit.
-- ---------------------------------------------------------------------------
INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P1-TRANSPORT-INTEGRATION', 1, '지구-궤도-행성표면 통합 수송', 'TRL', 0, 0.1000,
  'Y', 'ACTIVE',
  'One accountable campaign joins launch reuse, orbital logistics, refueling, deep-space propulsion, landing, ascent, and crew safety into an operational settlement route. Paper architectures, disconnected demonstrations, and incomplete one-way missions are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P2-SURVIVAL-INTEGRATION', 2, '26개월 무보급 생존·건강 유지', 'TRL', 0, 0.1400,
  'Y', 'ACTIVE',
  'The same continuously crewed off-Earth settlement operates all six life and health elements for at least 26 continuous months with normal operations, maintained crew health, and no Earth-origin material arrival. Uncrewed, analog-only, emergency, interrupted, or projected periods are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P3-HABITAT-INTEGRATION', 3, '26개월 표면 거주 인프라 통합 운용', 'TRL', 0, 0.1800,
  'Y', 'ACTIVE',
  'Constructed habitat, grid, communications, thermal control, and dust control operate together for the same occupied settlement and qualifying interval. Unoccupied analogs, independent subsystem tests, sorties, and nominal-only operation are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P4-RESOURCE-INTEGRATION', 4, 'ISRU-전력-저장-제조 통합 운용', 'TRL', 0, 0.1800,
  'Y', 'ACTIVE',
  'Local extraction, energy conversion, material qualification, storage, and manufacturing exchange audited resources as one settlement production chain. Independent pilots, imported feedstock, unloaded power, and modeled outputs without production are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P5-OPS-INTEGRATION', 5, '장기 자율기지 통합 운영', 'TRL', 0, 0.2500,
  'Y', 'ACTIVE',
  'Construction robotics, operational autonomy, and physical maintenance share mission state, authority, and recovery workflows under communication delay. Software-only demos, unrelated robots, real-time Earth control, and nominal operation without restoration are excluded.',
  'nodes-v1.0'
);

INSERT INTO capability_node (
  code, pillar, name_ko, scale_type, current_level, weight,
  is_integration_node, node_status, description, node_set_version
) VALUES (
  'P6-SETTLEMENT-INTEGRATION', 6, '정착 경제·법제·공급망 지속 운용', 'EGL', 0, 0.1800,
  'Y', 'ACTIVE',
  'Transport markets, binding governance, durable finance, insurance, standards, and contract mechanisms jointly sustain the same settlement through operational stress. Insulated state programs, unused instruments, emergency funding, and aspirational principles are excluded.',
  'nodes-v1.0'
);

-- ---------------------------------------------------------------------------
-- Mandatory pillar-local dependency edges. Edges cap effective readiness but
-- never infer or raise an integration node level.
-- ---------------------------------------------------------------------------
INSERT INTO capability_edge (to_node_id, from_node_id, or_group, delta_e)
SELECT target.id, source.id, 1, 0.150
  FROM capability_node target
  JOIN capability_node source ON source.pillar = target.pillar
 WHERE target.code = 'P1-TRANSPORT-INTEGRATION'
   AND source.is_integration_node = 'N';

INSERT INTO capability_edge (to_node_id, from_node_id, or_group, delta_e)
SELECT target.id, source.id, 1, 0.150
  FROM capability_node target
  JOIN capability_node source ON source.pillar = target.pillar
 WHERE target.code = 'P2-SURVIVAL-INTEGRATION'
   AND source.is_integration_node = 'N';

INSERT INTO capability_edge (to_node_id, from_node_id, or_group, delta_e)
SELECT target.id, source.id, 1, 0.150
  FROM capability_node target
  JOIN capability_node source ON source.pillar = target.pillar
 WHERE target.code = 'P3-HABITAT-INTEGRATION'
   AND source.is_integration_node = 'N';

INSERT INTO capability_edge (to_node_id, from_node_id, or_group, delta_e)
SELECT target.id, source.id, 1, 0.150
  FROM capability_node target
  JOIN capability_node source ON source.pillar = target.pillar
 WHERE target.code = 'P4-RESOURCE-INTEGRATION'
   AND source.is_integration_node = 'N';

INSERT INTO capability_edge (to_node_id, from_node_id, or_group, delta_e)
SELECT target.id, source.id, 1, 0.150
  FROM capability_node target
  JOIN capability_node source ON source.pillar = target.pillar
 WHERE target.code = 'P5-OPS-INTEGRATION'
   AND source.is_integration_node = 'N';

INSERT INTO capability_edge (to_node_id, from_node_id, or_group, delta_e)
SELECT target.id, source.id, 1, 0.150
  FROM capability_node target
  JOIN capability_node source ON source.pillar = target.pillar
 WHERE target.code = 'P6-SETTLEMENT-INTEGRATION'
   AND source.is_integration_node = 'N';
