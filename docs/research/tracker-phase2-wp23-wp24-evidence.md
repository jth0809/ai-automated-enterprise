# Tracker Phase 2 WP2.3/WP2.4 검증 증적

검증일: 2026-07-14 KST

검증 범위: `f277f5e..1234869` (`golden-set-v1`, 회귀 평가, 주간 실행, 관리도, 데드맨, 상태 동결/해제, drift 훈련)

## 결과 요약

- 집중 테스트: 68/68 통과, 실패·오류·건너뜀 0
- 백엔드 전체 회귀: 351/351 통과, 실패·오류·건너뜀 0
- Flyway: 빈 H2 Oracle-mode DB에 V1~V9 9개 migration 적용 및 검증 통과
- GitOps egress 정책 검사: 통과, `network-policy.yaml` 변경 0
- 신규 workload·CronJob·queue·vector DB: 0
- 평문 비밀 패턴: WP2.3/WP2.4 변경분에서 발견 0

## 골든셋과 저장량

| 항목 | 관측값 | 계약 |
|---|---:|---:|
| 파일 크기 | 34,305 B | 256 KiB 이하 |
| SHA-256 | `475beaa3b512bd8db5d1dcc1e05db9d597f90c624c387ed81625afff98216917` | 버전별 고정 |
| active case | 50 | 정확히 50 |
| `SYNTHETIC` | 26 | 허용 provenance |
| `HUMAN_PARAPHRASE` | 24 | 허용 provenance |
| 고유 `caseCode` | 50 | 중복 0 |
| 최대 title/body UTF-8 | 60 B / 156 B | 300 B / 2,000 B 이하 |

원문 기사, HTML, PDF, 이미지, 응답 본문은 보관하지 않는다. 골든 결과에는 canonical hash, mismatch field, error code만 저장하고 모델 원문 응답은 저장하지 않는다.

## DB 행 수와 bounded 계약

- loader 통합 테스트: `golden_set_dataset=1`, `golden_set_item=50`; 같은 hash 재실행은 no-op.
- 단일 평가/DRILL: `golden_set_run=1`, `golden_set_result=50`.
- drift 훈련: 44/50 일치, `ops_action_log=2`(자동 `FREEZE` 1 + 인간 `RELEASE` 1).
- 관리도는 최근 28일, populated 14일, 고정 4개 metric만 읽고 일별 bounded row를 upsert한다.
- 데드맨은 active RSS source 최대 16개, source별 publication timestamp 최대 64개만 읽는다.

## 스케줄러와 자원

- tracker 패키지의 `@Scheduled` 진입점은 총 11개다.
- Phase 2에서 추가한 진입점은 `GoldenSetJob`과 `PipelineMonitorJob` 2개다.
- 두 작업은 기존 Spring Boot pod 안에서 ShedLock으로 실행된다. 별도 pod, Kubernetes CronJob, 외부 알림 endpoint를 추가하지 않았다.
- GitOps 변경은 기존 deployment에 `TRACKER_GOLDEN_LIVE_ENABLED=false` 4줄을 더한 것뿐이며 CPU/memory 요청·제한은 바꾸지 않았다.

## 안전 경계

- LIVE 실행은 `TRACKER_GOLDEN_LIVE_ENABLED=true`의 명시적 GitOps 변경과 유효한 API 설정이 함께 있어야 한다.
- OFFLINE_REPLAY와 DRILL은 LIVE baseline을 갱신하지 않는다.
- LIVE agreement가 0.90 미만이거나 활성 baseline이 8일을 넘겨 stale이면 상태 갱신을 자동 동결한다.
- 관리도는 두 번 연속 위반일 때 동결한다. 데드맨 ALERT는 수집 경보만 내고 상태를 동결하지 않는다.
- 해제는 nonblank 인간 사유와 감사 로그를 요구한다.

## 실행 명령

```powershell
mvn.cmd -o -Dmaven.repo.local=target/codex-m2 -Dtest=TrackerPhase2QualitySchemaTest,GoldenSetDatasetValidatorTest,GoldenSetLoaderTest,GoldenOutputTest,GoldenSetEvaluatorTest,GoldenSetJobTest,DeepClassifierGoldenAdapterTest,StateFreezeServiceTest,StateFreezeFailClosedTest,ControlChartTest,PipelineMonitorJobTest,DeadmanMonitorTest,FeedDeadmanRepositoryTest,CircuitBreakerDrillTest,StateUpdaterTest,StateUpdaterFreezeBoundaryTest,SnapshotJobTest,TrackerAdminControllerTest test
mvn.cmd -o -Dmaven.repo.local=target/codex-m2 test
.\gitops\apps\backend-springboot\tests\tracker-egress-policy.ps1
git diff --check
```

두 Maven 실행은 각각 새 Surefire JVM에서 수행했다. 의존성은 네트워크 호출 없이 모듈 전용 캐시를 사용했고, Maven은 main/test class가 현재 source와 일치해 재컴파일 대상이 없다고 판정했다.

로컬 환경에는 Gitleaks 실행 파일이 없어 CI의 `gitleaks/gitleaks-action@v3` 전체 이력 검사는 PR 게이트로 남는다. 로컬에서는 이번 범위의 diff에 대해 고신뢰 API key/token/private-key 패턴 검사를 수행해 0건을 확인했다.
