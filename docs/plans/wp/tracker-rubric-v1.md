# WP0.3 — 루브릭 r1.0 보관본 (노드 세트 v0.1)

> 상태: **보관됨**. 이 문서의 20노드 목록과 프롬프트 구조는
> `rubric_version='r1.0'`, `node_set_version='nodes-v0.1'`의 역사적 시드다.
> 현재 승인 레지스트리는 [Tracker Capability Nodes v1.0](tracker-nodes-v1.md)이며,
> 활성 루브릭은 `r2.0`이다. LF 정규화 Stage 2 프롬프트 SHA-256은
> `bb77587b3d5d47971251d058ba54b41f47ea0a1b9df21b372b42933aec7a36b0`이다.

## 1. 노드 세트 v0.1 (Phase 1 시드, 20개 — P2에서 35개로 확장)

| 코드 | 필라 | 이름 | 척도 | 초기 가중치 |
|------|------|------|------|-------------|
| P1-REUSE-LV | 1 | 완전 재사용 발사체 | TRL | 0.25 |
| P1-ORBIT-REFUEL | 1 | 궤도 추진제 이송·급유 | TRL | 0.25 |
| P1-DEEP-PROP | 1 | 심우주 추진 (NTP/전기추진) | TRL | 0.15 |
| P1-EDL-HEAVY | 1 | 대형(10t+) 화물 행성 진입·강하·착륙 | TRL | 0.20 |
| P1-SURFACE-ASCENT | 1 | 행성 표면 이륙·귀환 | TRL | 0.15 |
| P2-ECLSS | 2 | 폐쇄 순환 생명 유지 (물·공기) | TRL | 0.35 |
| P2-FOOD | 2 | 우주·행성 표면 식량 생산 | TRL | 0.25 |
| P2-RAD | 2 | 방사선 방호 (심우주·표면) | TRL | 0.20 |
| P2-MED | 2 | 저중력 장기 체류 의학 | TRL | 0.20 |
| P3-CONSTRUCT | 3 | 표면 거주지 건설 (현지 재료 포함) | TRL | 0.40 |
| P3-POWER | 3 | 표면 전력 계통 (발전·저장·배전) | TRL | 0.35 |
| P3-COMMS | 3 | 행성 간·표면 통신망 | TRL | 0.25 |
| P4-ISRU-PROP | 4 | ISRU: 추진제·물·산소 현지 생산 | TRL | 0.45 |
| P4-NUKE | 4 | 우주용 소형 원자로/핵융합 | TRL | 0.35 |
| P4-MATERIALS | 4 | 극한환경 구조 소재 | TRL | 0.20 |
| P5-AUTOCON | 5 | 무인 선행 건설·조립 로봇 | TRL | 0.50 |
| P5-AUTONOMY | 5 | 장주기 자율 운영 (항법·정비·이상 대응) | TRL | 0.50 |
| P6-LAUNCH-MARKET | 6 | 발사 시장·수송 경제성 | EGL | 0.40 |
| P6-GOV-FRAMEWORK | 6 | 우주 자원·거주 국제 규범/법제 | EGL | 0.35 |
| P6-FUNDING | 6 | 지속 가능 자금 조달·민간 투자 | EGL | 0.25 |

- 통합 실증 노드와 26개월 무보급 관측 조건은 승인된 `nodes-v1.0`에서
  추가되었다. 아래 v0.1 목록은 재현·감사용으로만 유지한다.
- 이 문서의 초기 가중치는 현재 값을 나타내지 않는다.

## 2. Stage 1 게이트 프롬프트 (전문)

```
You are a relevance gate for a space-settlement technology tracker.

Answer YES only if the article reports on technology, missions, operations,
economics, or governance DIRECTLY related to human expansion beyond Earth:
launch vehicles, in-space propulsion, orbital refueling, planetary landing,
life support, space agriculture, radiation protection, space medicine,
surface habitats/construction/power/communications, ISRU, space nuclear
power, space-applied robotics/autonomy, launch markets, space law/treaties,
or space-settlement funding.

Answer NO for: general AI/tech news without explicit space application,
astronomy/astrophysics discoveries without engineering relevance, defense/
military satellite news, Earth-observation business news, entertainment,
and opinion pieces without a reported event.

Respond with exactly one word: YES or NO.

Title: {title}
Excerpt: {excerpt_or_first_1000_chars}
```

## 3. Stage 2 심층 분류 시스템 프롬프트 (r1.0 보관 구조)

```
[블록 1 — 역할·원칙]
You classify space-settlement news into structured claims. You NEVER assign
scores. Extract only what the text supports; every claim requires a verbatim
evidence quote copied exactly from the article. If the article supports no
claim about the registered capability nodes, return an empty list.

[블록 2 — 노드 레지스트리]  (1절의 20개 노드: 코드 + 이름 + 1줄 정의)

[블록 3 — 사건 유형 정의]   (12종 enum 각각의 판정 기준 1~2문장. 핵심 구분:
  - ANNOUNCEMENT_ONLY: 계획·일정·목표의 발표. 성취가 아님. NASA/기업의 로드맵 발표는 전부 이것.
  - FLIGHT_TEST vs OPERATIONAL_DEPLOYMENT: 시험 비행 vs 반복적 실운용.
  - INSTITUTIONAL_ADVANCE: 필라 6의 전진 — 조약 채택·비준, 입법, 시범 계약, 최초 매출 등.
  - ROLLBACK: 필라 6 한정 — 조약 탈퇴, 법 폐지, 시장 붕괴의 '실행'(시사·검토는 ANNOUNCEMENT_ONLY).
  - RETROSPECTIVE: 과거 사건의 회고·기념 보도. occurred_on은 원 사건 날짜.)

[블록 4 — 성숙도 판정 기준]  (TRL 1~9 요약 정의 + EGL 1~9 이원 트랙 표(컨셉 5.1) +
  "claimed_level은 이 사건이 입증했다고 기사가 주장하는 수준이다. 노드 정의에 미달하는
   부분 실증(예: 대형 화물 착륙 노드에 대한 1t급 착륙)은 그만큼 낮은 level로 매긴다.")

[블록 5 — 역사적 앵커 예시 12건]  (아래 4절 표를 입출력 예시 형식으로 포함)

[블록 6 — 출력 규칙]
Use the classify_article tool. occurred_on = event date, not publication date.
publication_path: PRIMARY if the source is the actor itself (press release,
company blog), THIRD_PARTY for independent reporting, WIRE_REPRINT if the
text is a republished press release. duplicate_hint: compare against the
recent-events list in the user message; return the matching natural_key or "NEW".
```

유저 메시지(비캐싱): 기사 제목·본문 + 관련 노드의 최근 사건 natural_key 목록(≤20).

## 4. 역사적 앵커 12건 (블록 5 내용, 골든셋 검수 전 초안)

| # | 사건 (연도) | node_code | event_type | claimed_level | 교훈 포인트 |
|---|---|---|---|---|---|
| 1 | 팰컨9 1단 최초 지상 착륙 (2015) | P1-REUSE-LV | FLIGHT_TEST | 5 | 1단 실증은 완전 재사용 발사체의 구성요소 근거 |
| 2 | 팰컨9 재사용 부스터 상업 재비행 (2017) | P1-REUSE-LV | FLIGHT_TEST | 5 | 반복 1단 재사용도 전체 스택 자격은 아님 |
| 3 | 팰컨9 재사용 정례화·주력화 (2019~) | P1-REUSE-LV | OPERATIONAL_DEPLOYMENT | 5 | 높은 빈도도 소모성 상단 제외 조건을 해소하지 않음 |
| 4 | 스타십 차량 내부 탱크 간 추진제 이송 실증 (IFT-3, 2024) | (해당 없음) | — | — | 한 차량 내부 이송은 궤도 급유 노드에서 제외 |
| 5 | MOXIE 화성 표면 산소 생산 (2021) | P4-ISRU-PROP | FLIGHT_TEST | 5 | 실환경 공정 근거지만 운영 규모 미달 |
| 6 | Kilopower/KRUSTY 지상 원자로 시험 (2018) | P4-NUKE | PROTOTYPE_DEMO | 5 | 지상 프로토타입 |
| 7 | 中 웨궁-365 폐쇄 생태 370일 유인 실험 (2018) | P2-ECLSS | PROTOTYPE_DEMO | 5 | 지상 시뮬레이션 상한 |
| 8 | 퍼서비어런스 EDL 성공 (2021) | P1-EDL-HEAVY | FLIGHT_TEST | 5 | 명시된 하위 규모 L5 앵커이며 L8 근거가 아님 |
| 9 | 아르테미스 협정 서명 (2020) | P6-GOV-FRAMEWORK | INSTITUTIONAL_ADVANCE | 3 | 공식 비구속 원칙은 법적 효력 규칙보다 낮음 |
| 10 | 스타십 IFT-1 공중 폭발 (2023) | P1-REUSE-LV | SETBACK | (레벨 없음) | 상태 유지, 추세 하향 |
| 11 | "NASA, 2030년대 유인 화성 로드맵 발표" | P1-DEEP-PROP 등 | ANNOUNCEMENT_ONLY | (레벨 없음) | 계획은 성취가 아님 — 상태 0 |
| 12 | "ISS 20주년: 인류 우주 상주 회고" | P2-ECLSS | RETROSPECTIVE | (레벨 없음) | occurred_on = 원 사건일, 상태 무변화 |

## 5. 검증 수준 도출 규칙 (코드 명세 — LLM 무개입, 컨셉 6절)

```
입력: 사건 클러스터의 (source_registry.tier, source_type, publication_path) 목록
서열: CLAIMED < PEER_REVIEWED < OFFICIAL < INDEPENDENT  (복수 충족 시 최고 적용)

INDEPENDENT   ⟸ 서로 다른 source_id의 Tier 1~2 항목 ≥ 2 이고
                각각 publication_path != WIRE_REPRINT
OFFICIAL      ⟸ source_type = AGENCY(Tier 1) 항목 존재
PEER_REVIEWED ⟸ source_type = JOURNAL(Tier 1) 항목 존재   ※ PREPRINT(arXiv)는 불충족
CLAIMED       ⟸ 그 외 전부 (CORPORATE 직접 발표, Tier 3 단독, PREPRINT 단독)
```

상태 갱신 게이트: `PEER_REVIEWED 이상` 확정 전진. 단 **도달 판정(1절)과 ROLLBACK은 OFFICIAL 이상**, TRL/EGL 8~9 전진은 검증 수준과 무관하게 인간 검수 필수.

## 6. 결정론적 채점 명세 (코드 — 컨셉 7절)

```
base:   level 1→1, 2→2, 3→3, 4→4, 5→5, 6→6, 7→8, 8→9, 9→10
verify: CLAIMED 0.3 | PEER_REVIEWED 0.7 | OFFICIAL 0.9 | INDEPENDENT 1.0
novelty:
  1.0 ⟸ claimed_level > node.current_level (실전진)
      또는 node_status=DORMANT 이고 재시연 (복원)
  0   ⟸ 그 외 (반복 시연·후속 보도)
impact_score = base(claimed_level) × verify × novelty
event_type ∈ {ANNOUNCEMENT_ONLY, RETROSPECTIVE} → 상태·점수 경로 진입 금지 (기록만)
SETBACK / PROGRAM_CANCELLATION → 점수 없음, 상태 유지 (실운용 취소는 배치 해제 + 휴면 카운트다운)
ROLLBACK (P6, OFFICIAL+) → current_level을 claimed_level로 하향 + history 기록
복원 규칙: DORMANT 노드의 재시연은 "재시연이 입증한 수준까지만" 복원
```

검증 예 (파이프라인 불변식 테스트에 사용): 궤도 급유 6→7 전진 + OFFICIAL → 8×0.9×1.0 = **7.2** (검수 큐 미진입) / 8레벨 도달 + OFFICIAL → 9×0.9 = **8.1** (검수 필수 — 구조적 보장).
