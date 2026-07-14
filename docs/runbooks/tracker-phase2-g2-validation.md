# Tracker Phase 2 G2 검증 런북

> 증거 행렬: [tracker-phase2-g2-evidence.md](../research/tracker-phase2-g2-evidence.md)
>
> 이 런북은 G2 증거를 **fresh 재현**하는 명령과 기대 위치를 규정한다. 실제
> 실행 결과만 증거로 기록하고, 미실행 항목은 증거 행렬에 `PENDING`으로 남긴다.

## 사전 조건

- 작업트리 `.claude/worktrees/tracker-mvp`, 브랜치 `feat/tracker-mvp`.
- JDK 21과 저장소 Maven 3.9.9 wrapper. `apps/frontend/react-app`에 설치된
  node_modules(Vite/Vitest).
- Anthropic API 키를 test/로컬 profile에 넣지 않는다(LIVE_MODEL은 미활성 유지).

## 소프트웨어 회귀

```powershell
$Maven = (Get-ChildItem -LiteralPath "$HOME\.m2\wrapper\dists" -Recurse -Filter mvn.cmd |
  Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).FullName
Push-Location apps/backend/springboot-app
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
Pop-Location
Push-Location apps/frontend/react-app
& node_modules/.bin/vitest run
& npm run build
Pop-Location
git -c safe.directory=(Resolve-Path .).Path diff --check
```

기대: 백엔드 379/379, 프런트 65/65, `tsc && vite build` 성공, diff-check exit 0.

## 참조형 백필 구조·저장 증거

```powershell
$tracker = 'apps/backend/springboot-app/src/main/resources/tracker'
$claims = Get-Content -Raw -Encoding UTF8 "$tracker/backfill-v1.json" | ConvertFrom-Json
$cands = Get-Content -Encoding UTF8 "$tracker/historical-candidates-v1.jsonl"
[pscustomobject]@{
  candidates = $cands.Count
  claims = $claims.Count
  usedCandidates = ($claims.candidateId | Sort-Object -Unique).Count
  candidateBytes = (Get-Item "$tracker/historical-candidates-v1.jsonl").Length
  mappingBytes = (Get-Item "$tracker/backfill-v1.json").Length
}
$claims | Group-Object { $_.nodeCode.Substring(0,2) } | Sort-Object Name |
  Select-Object Name, Count
```

기대(2026-07-14 실측): candidates 213, claims 147, usedCandidates 141, 필라
P1~P6 = 32/21/25/21/23/25, 저장 합계 < 1 MiB. 금지 필드는
`BackfillDatasetValidator`가 키 단위로 차단하며 위 회귀(379 green)에 포함된다.

## 관제·품질

- 35-노드 구조: `-Dtest=TrackerNodesV1Test` (379 green에 포함).
- DRILL 동결/해제: [tracker-circuit-breaker-drill.md](tracker-circuit-breaker-drill.md).
- 골든 합의: 골든 평가/버전 실행 테스트(379 green). LIVE baseline으로 표기 금지.

## 콜드 스타트 증거 (완료, 참조)

- 35/35 현재상태 감사와 1957~현재 주간 cadence: WP2.2-B/C(`716c2c3`),
  [주간 백필 런북](tracker-weekly-backfill-validation.md).
- 골든 합의·OFFLINE/DRILL: WP2.3/2.4,
  [WP2.3/2.4 증적](../research/tracker-phase2-wp23-wp24-evidence.md).

## G2 게이트 폐쇄 전 남은 검증

1. PR CI 보안 스캔(Semgrep/Trivy/Gitleaks) 통과.
2. LIVE_MODEL 활성화 여부 결정(활성화 시 CNP allowlist + 보안 검토 동반).
   미활성이면 증거 행렬에 `NOT_ACTIVATED`로 유지.
3. 인간(사용자) G2 승인.
