# WP3.1 궤도 인류 체류 Layer B 검증 증거

검증일: 2026-07-16 (KST)

상태: **측정 범위 완료 / 실제 API·브라우저 검증 완료 / LL2 Layer C 승격은 LIVE_MODEL 승인 전까지 의도적으로 보류**

## 1. 측정 정의와 범위

- 전 세계 **궤도** 체류 인구를 합산한다. ISS, Tiangong, 궤도 자유비행 임무를
  포함하고 준궤도 비행은 제외한다.
- 검수된 인구 변동 시점을 UTC 구간으로 적분해 완료 연도의 `person-days`를
  계산하고, 같은 구간에서 연중 최대 동시 궤도 인원을 계산한다.
- 생성 지표는 Pillar 2의 Layer B `MEASURED` 관측일 뿐이다. node, event,
  history, snapshot, readiness, ETA를 만들거나 변경하지 않는다.
- 원천은 [Jonathan's Space Report orbital population history](https://planet4589.org/space/astro/web/pop.html)이며,
  연간 집계 정의는 [annual statistics 설명](https://planet4589.org/space/astro/web/annual.html)을
  함께 검토했다. 저장소에는 검수한 전이 CSV와 출처 메타데이터만 두며 원문 페이지를
  저장하지 않는다.

## 2. 결정적 집계 결과

| 완료 연도 | 궤도 인류 체류 | 최대 동시 궤도 인원 |
|---|---:|---:|
| 2024 | 4,241.8711 인일 | 19명 |
| 2025 | 3,922.2028 인일 | 14명 |

프로덕션 CSV는 `complete_through_utc=2026-01-01T00:00:00Z`를 명시한다.
그 이후 초안 전이는 검증할 수 있지만 완료 연도 집계에는 포함하지 않는다. 윤년,
연도 경계 clipping, 전이 사이 인구 지속, 마지막 4자리 `HALF_UP` 반올림을 순수
집계 테스트로 고정했다.

## 3. 검증·가져오기 계약

- UTF-8, 정확한 5개 메타데이터와 헤더, UTC `Z`, 엄격 증가 시각, 인구 `0..50`,
  완료 경계와 각 1월 1일 경계를 검증한다.
- 입력은 256 KiB, 전이는 5,000개로 제한한다. 출처 URL은 정확히
  `https://planet4589.org:443`만 허용한다.
- 원본 CSV SHA-256과 `human-presence-v1`을 기존 V11 import 원장에 잠근다.
  같은 버전·같은 해시는 no-op이고, 같은 버전·다른 해시와 기존 자연키의 값·출처
  충돌은 쓰기 전에 실패한다.
- 한 트랜잭션과 ShedLock `tracker-human-presence-import` 안에서 다음 네 행만 만든다.
  `ANNUAL_ORBITAL_HUMAN_PERSON_DAYS` 2개와
  `MAX_SIMULTANEOUS_HUMANS_IN_ORBIT` 2개다.
- 부트 가져오기는 로컬 classpath 리소스만 읽는다. 신규 egress, secret, pod,
  Kubernetes CronJob, 모델 호출이 없다.

## 4. 실행된 자동 검증

- Task 1 validator·aggregator·production corpus: **12/12 통과**
- Task 2 loader·allowlist·API·config: **19/19 통과**
- Layer B 패널·Tracker 페이지 집중 Vitest: **7/7 통과**
- 백엔드 전체: **600 tests, failures 0, errors 0, skipped 0, BUILD SUCCESS**
- 현재 작업 트리 프런트 전체: **17 files, 79/79 통과**
- 잠금파일 정확 도구체인 격리 검증:
  Vite 8.1.4, `@vitejs/plugin-react` 6.0.3, TypeScript 7.0.2,
  Vitest 4.1.10, **17 files / 79/79 통과**, production build 통과
  (`37 modules transformed`, CSS 22.29 kB, JS 231.82 kB)
- `git diff --check`: 통과(CRLF 변환 경고만 존재)
- GitOps verifier: `OK: tracker egress policy and safe defaults verified`
- `kubectl kustomize gitops/apps/backend-springboot`: 렌더 성공. tracker와 모든 live
  collector 플래그는 false이고, 사람 체류 classpath import만 true다.
- Gitleaks 실행 파일은 로컬에 없었다. 대신 tracked diff와 신규 Phase 3 파일의
  private-key/AWS-key/토큰 리터럴 패턴을 검사해 **0건**을 확인했다.

## 5. 실제 API·화면 확인

Spring을 `test,demo,refbackfill` 프로필로 8080에 기동하고 표준 Vite 프록시를
5176에 연결했다.

- `GET /api/tracker/layer-b`: 최신 2025년 두 행을 반환했다.
  `3,922.2028 PERSON_DAYS`, `14 PEOPLE`, Pillar 2, `MEASURED`, 확인일
  `2026-07-16`, 검수 출처 URL을 확인했다.
- Tracker 탭은 존재하고 활성화됐다. 화면에는 `연간 궤도 인류 체류`,
  `3,922.2028 인일`, `연중 최대 동시 궤도 인원`, `14 명`, 관측일·검수일·출처,
  `전 세계 궤도 기준 · 준궤도 제외 · 자동 점수 효과 없음`이 표시됐다.
- 375px 검증에서 문서 자체 가로 넘침은 없고 Layer B는 viewport 안에 유지됐다.
  4열 예측표만 920px 내부 폭을 자체 가로 스크롤했다.
- 브라우저 console error: **0건**.

## 6. 의도적으로 남긴 활성화 경계

LL2를 Layer C 사건 채널로 승격하는 경로는 구현·실행하지 않았다. 이는 누락이 아니라
사용자가 승인한 `LIVE_MODEL 비활성` 경계다. 현재 Phase 3의 측정·앵커·관측 게이트는
비-AI Layer B와 정직한 대조 패널로 닫으며, LL2 Layer C 승격과 모든 live polling은
별도 인간 승인 후의 활성화 작업으로 유지한다.
