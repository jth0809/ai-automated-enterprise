# Tracker Phase 2 35노드 현재상태 감사

- 감사 기준일: 2026-07-14 UTC
- 후보 코퍼스: `historical-candidates-v1.jsonl` 213건, READY 213, REJECTED 0
- 생산 청구: `backfill-v1.json` 147건
- 감사 행렬: `backfill-audit-v1.json` 35행
- 노드/루브릭: `nodes-v1.0` / `r2.0`
- 저장 정책: 공식 URL·locator·접근일·SHA-256·직접 작성한 짧은 사실 요약만 저장

## 결론

35개 노드를 모두 날짜순으로 재생한 결과 P1의 `DEEP-PROP`과
`SURFACE-ASCENT`만 DORMANT이고 나머지는 ACTIVE다. 직접 통합 증거가 없는
`P4-RESOURCE-INTEGRATION`과 `P6-SETTLEMENT-INTEGRATION`은 L0을 유지한다.
L0은 부품 연구가 없다는 뜻이 아니라, 같은 시스템이 통합 운용됐다는 직접
근거가 공식 검색 범위에서 확인되지 않았다는 뜻이다.

이번 감사는 readiness, ETA, L0 수, 청구 수를 목표로 삼지 않았다. AMOS와
LSSIF처럼 하위 경계를 직접 충족하는 근거에는 L3 부분점수를 인정했지만,
다음 단계의 실제 도달 조건은 완화하지 않았다. VIPER 종료는 개별 프로젝트
종료이므로 `P6-FUNDING` 전체의 프로그램 종료로 전파하지 않았다.

## 현재 준비도

`params-v1`의 현행 매핑과 휴면 감쇠를 그대로 적용한 값이다. 이는 감사
결과이지 목표값이 아니다.

| 필라 | 노드 수 | 현재 준비도 |
|---|---:|---:|
| P1 수송·추진 | 8 | 0.4624 |
| P2 생존·건강 | 7 | 0.1884 |
| P3 거주·인프라 | 6 | 0.1524 |
| P4 자원·에너지 | 5 | 0.1920 |
| P5 자율 운영 | 4 | 0.2991 |
| P6 경제·제도 | 5 | 0.2667 |

## 35/35 노드 행렬

`레벨 근거`는 현재 레벨을 직접 지지하는 상태 청구와 evidence ref다.
`상태 근거`는 휴면 또는 비휴면 판정에 직접 관련된 비상태 청구다. 전체 문구와
두 검토 레인은 기계 판독 행렬 `backfill-audit-v1.json`이 정본이다.

| 노드 | 현재 | 레벨 근거 | 상태 근거 | 다음 단계의 미충족 조건 |
|---|---|---|---|---|
| `P1-REUSE-LV` | L5 ACTIVE | `BF-P1-022` / `HC-2021-FALCON9-100-RECOVERIES#NASA` | — | 모든 임무 핵심 단·우주선의 반복 회수·재비행 |
| `P1-ORBIT-REFUEL` | L8 ACTIVE | `BF-P1-031` / `HC-2008-JULES-VERNE-ISS-REFUEL#ESA` | `BF-P1-006` NONE | 다임무 정착 규모 depot 이송의 반복 운영 |
| `P1-DEEP-PROP` | L5 DORMANT | `BF-P1-008` / `HC-1969-NERVA-XE-GROUND-TESTS#NASA` | `BF-P1-009` capability end | 정착 규모 추력·전력·열·수명 체인의 심우주 비행 |
| `P1-EDL-HEAVY` | L5 ACTIVE | `BF-P1-013` / `HC-2022-LOFTID-REENTRY-DEMO#NASA` | — | 비지구 대기권에 사용 화물 10t 이상 착륙 |
| `P1-SURFACE-ASCENT` | L8 DORMANT | `BF-P1-016` / `HC-1969-APOLLO11-LUNAR-ASCENT#NASA` | `BF-P1-032` capability end | 현재 운용 가능한 반복 표면 이륙 물류선 |
| `P1-CREW-SAFE` | L8 ACTIVE | `BF-P1-018` / `HC-2018-SOYUZ-MS10-ABORT-RECOVERY#NASA` | — | 전체 정착 수송 경로의 반복 종단간 비상 대응 |
| `P1-ORBIT-LOGISTICS` | L8 ACTIVE | `BF-P1-030` / `HC-2014-FIRST-OPERATIONAL-CYGNUS-RESUPPLY#NASA` | — | LEO 밖 정착 규모 다기관 표준 물류 운영 |
| `P1-TRANSPORT-INTEGRATION` | L3 ACTIVE | `BF-P1-026` / `HC-2021-PERSEVERANCE-MARS-LANDING#NASA` | — | P1 전 요소를 한 책임 캠페인으로 운용 |
| `P2-ECLSS` | L3 ACTIVE | `BF-P2-004` / `HC-2023-ISS-98PCT-WATER-RECOVERY#NASA` | — | 정착 승무원 부하에서 대기·물 폐쇄루프 지속 |
| `P2-FOOD` | L3 ACTIVE | `BF-P2-008` / `HC-2021-ISS-PEPPER-SECOND-HARVEST#NASA` | — | 영양 수요의 유의미한 몫을 지속·안전 생산 |
| `P2-RAD` | L5 ACTIVE | `BF-P2-012` / `HC-2022-ARTEMIS1-ORION-RADIATION-VALIDATION#NASA` | — | 유인 심우주·표면에서 지속 선량 통제 운영 |
| `P2-MED` | L5 ACTIVE | `BF-P2-014` / `HC-2012-ARED-BONE-STUDY#NASA` | — | 장기 부분중력 임상 결과와 대응책 |
| `P2-WASTE-CYCLE` | L3 ACTIVE | `BF-P2-018` / `HC-2019-OSCAR-SUBORBITAL-TRASH-TO-GAS#NASA` | — | 유인 거주지에서 폐기물의 안전한 반복 자원화 |
| `P2-HEALTH-AUTONOMY` | L3 ACTIVE | `BF-P2-019` / `HC-2020-ISS-AMOS-FIRST-AUTONOMOUS-SCANS#NASA` | — | 지구 실시간 지원 없는 해석·진단·치료 선택·시행 |
| `P2-SURVIVAL-INTEGRATION` | L5 ACTIVE | `BF-P2-021` / `HC-2000-ISS-PERMANENT-OCCUPANCY#NASA` | — | 지구 기원 물질 유입 없이 P2 전 요소 26개월 연속 운영 |
| `P3-CONSTRUCT` | L3 ACTIVE | `BF-P3-005` / `HC-2021-MARS-DUNE-ALPHA-PRINTED#NASA` | — | 현지 재료로 목적지 거주지 구조 건설·검증 |
| `P3-POWER` | L3 ACTIVE | `BF-P3-022` / `HC-2021-ISS-IROSA-FIRST-INTEGRATION#NASA` | — | 목적지 표면 계통의 발전·저장·배전·고장복구 지속 |
| `P3-COMMS` | L3 ACTIVE | `BF-P3-024` / `HC-2024-LN1-LUNAR-NAVIGATION-BEACON#NASA` | — | 목적지 전역 상호운용 통신·항법망의 지속 운영 |
| `P3-THERMAL` | L3 ACTIVE | `BF-P3-025` / `HC-1995-LSSIF-INTEGRATED-THERMAL-TEST#NASA` | — | 목적지 주기·고장을 통과한 표면 거주지 열제어 |
| `P3-DUST` | L3 ACTIVE | `BF-P3-019` / `HC-2025-BLUE-GHOST-RAC-DUST-MEASUREMENT#NASA` | — | 수트·에어록·거주지·기구 전체의 통합 먼지 통제 |
| `P3-HABITAT-INTEGRATION` | L5 ACTIVE | `BF-P3-020` / `HC-2024-CHAPEA-378-DAY-ANALOG#NASA` | — | 비지구 목적지의 실제 주기·물류·고장 속 통합 거주 |
| `P4-ISRU-PROP` | L5 ACTIVE | `BF-P4-006` / `HC-2023-MOXIE-MISSION-COMPLETION#NASA` | `BF-P4-004` NONE | 현지 추진제·물·산소의 정착 규모 지속 생산·사용 |
| `P4-NUKE` | L5 ACTIVE | `BF-P4-008` / `HC-2018-KRUSTY-FULL-POWER-GROUND-TEST#NASA` | — | 비행·표면에서 전체 변환·열배출·부하 체인 운영 |
| `P4-MATERIALS` | L3 ACTIVE | `BF-P4-014` / `HC-2019-ISS-CEMENT-HYDRATION-STUDY#NASA` | — | 복합 서비스 하중·수명에서 목적지 구조부품 검증 |
| `P4-MANUFACTURING` | L3 ACTIVE | `BF-P4-020` / `HC-2024-FIRST-METAL-3D-PRINT-IN-SPACE#NASA` | `BF-P4-019` NONE | 현지·재활용 원료로 유용 부품 생산·검증 |
| `P4-RESOURCE-INTEGRATION` | L0 ACTIVE | — | — | 한 시스템의 채취·처리·저장·분배·사용 직접 통합 증거 |
| `P5-AUTOCON` | L3 ACTIVE | `BF-P5-004` / `HC-2024-ARMADAS-AUTONOMOUS-GROUND-ASSEMBLY#NASA` | `BF-P5-003` NONE | 목적지 정착 규모 인프라의 자율 건설·인수 검증 |
| `P5-AUTONOMY` | L7 ACTIVE | `BF-P5-010` / `HC-2021-PERSEVERANCE-AUTONAV#NASA` | — | 제한된 지구 지원으로 정착 전체 운영을 지속 |
| `P5-MAINTENANCE` | L5 ACTIVE | `BF-P5-019` / `HC-2007-ORBITAL-EXPRESS-SERVICING-TRANSFERS#NASA` | — | 실제 고장에서 정착 장비를 자율 진단·물리 복구 |
| `P5-OPS-INTEGRATION` | L3 ACTIVE | `BF-P5-020` / `HC-2025-ISAAC-FAULT-LOGISTICS-DEMONSTRATIONS#NASA` | — | 목적지에서 건설·운영·물류·정비의 지속 통합 |
| `P6-LAUNCH-MARKET` | L6 ACTIVE | `BF-P6-004` / `HC-2024-FAA-148-LICENSED-OPERATIONS#FAA` | — | 여러 주기를 견디는 정착 물류 시장·가격·수요 |
| `P6-GOV-FRAMEWORK` | L6 ACTIVE | `BF-P6-009` / `HC-2015-US-COMMERCIAL-SPACE-RESOURCE-LAW#GOVINFO` | — | 정착 전 분야를 포괄하는 구속력·집행 가능 제도 |
| `P6-FUNDING` | L3 ACTIVE | `BF-P6-015` / `HC-2021-COMMERCIAL-DESTINATION-DESIGN-AWARDS#NASA` | `BF-P6-016` NONE | 정치·벤처 한 주기를 넘는 반복 자본·운영 금융 |
| `P6-INSURANCE-STD` | L3 ACTIVE | `BF-P6-019` / `HC-2022-LUNANET-INTEROPERABILITY-SPEC-V4#NASA` | — | 보험·인증·표준·지급·에스크로의 반복 실사용 |
| `P6-SETTLEMENT-INTEGRATION` | L0 ACTIVE | — | — | 시장·금융·제도·표준·위험이 한 정착 경제로 운용된 직접 증거 |

## 핵심 재감사 근거

### P2 HEALTH-AUTONOMY

NASA의 AMOS 기록은 ISS 승무원이 통상적인 실시간 지상 유도 없이
just-in-time 절차 지원으로 자율 초음파 촬영을 수행한 operational
proof-of-concept임을 지지한다. 따라서 영상 획득·절차 실행 범위의 L3 부분점수는
인정한다. 다만 자율 해석, 진단, 치료 선택과 치료 시행은 입증되지 않았으므로
L4 이상으로 올리지 않았다.

- NASA NTRS: https://ntrs.nasa.gov/citations/20220014327
- NASA AMO 프로그램: https://www.nasa.gov/directorates/stmd/game-changing-development-program/autonomous-medical-operations-amo/

### P3 THERMAL

NASA LSSIF 시험은 승무원 체류 가능 챔버의 생명유지 계통에 냉각수를 공급하고
달 환경을 모사한 열 싱크로 폐열을 버린 통합 열제어 시제품이었다. 이는 L3
부분점수를 지지한다. 지상 챔버였고 실제 표면 거주지가 목적지 주기와 고장을
통과한 것은 아니므로 L4 이상은 인정하지 않았다.

- NASA NTRS: https://ntrs.nasa.gov/citations/19970001714

### P6 FUNDING

NASA의 공식 발표는 VIPER를 중단하면서도 CLPS, 대체 자원 탐사 임무와 후속
기기 활동은 계속한다고 명시한다. 따라서 이 사건은 `PROGRAM_CANCELLATION`이되
`programEndEffect=NONE`이며 P6 자금조달 노드의 휴면 시계를 시작하지 않는다.

- NASA VIPER 발표: https://www.nasa.gov/news-release/nasa-ends-viper-project-continues-moon-exploration/

## 검토·무결성 계약

행렬의 `phase2-fact-audit`와 `phase2-rubric-audit`는 사실 직접성과 루브릭
경계를 분리한 두 검토 레인이다. 서로 다른 제3자 개인이 참여했다고 주장하지
않으며, 실제 운영 모델 활성화와 정식 릴리스는 별도 인간 승인 게이트를 거친다.

자동 검증은 다음을 강제한다.

- 정확히 35개 고유 노드와 `nodes-v1.0` / `r2.0`
- 현재 레벨 청구와 evidence ref의 정확한 연결
- L0 노드의 빈 레벨 근거
- 휴면 노드의 `CAPABILITY_PROGRAM_END` 상태 근거
- 개별 프로젝트 취소와 node-wide 종료의 분리
- 날짜순 감사 재생 상태와 fresh DB import 상태의 35/35 일치
- 두 승인 레인의 서로 다른 ID와 FACT/RUBRIC 역할 분리
- 감사 파일 256 KiB 이하, 후보 2 MiB 이하, 매핑 1 MiB 이하

현재 파일 크기는 후보 약 209 KiB, 매핑 약 135 KiB, 감사 행렬 약 26 KiB다.
어떤 파일에도 출처 본문, HTML, PDF, 이미지 또는 WARC를 저장하지 않는다.
