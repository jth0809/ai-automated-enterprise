# 런북: 시크릿 로테이션 (Secret Rotation)

이 저장소·클러스터가 의존하는 모든 자격증명의 **위치 / 로테이션 주기 / 절차 / 유출 시 blast radius / 탐지 방법**을 기록한다. 원칙: 시크릿은 Git에 없다(스캔은 Gitleaks 워크플로가 강제). 대부분은 OCI Vault를 신뢰 루트로 하고 External Secrets Operator가 클러스터로 동기화한다.

기준 시점: 2026-07-08. 관련: [BACKLOG_BY_DOMAIN.md](../../BACKLOG_BY_DOMAIN.md) 보안 P2.

## 인벤토리 (한눈에)

| # | 시크릿 | 위치 | 주기 | 유출 시 영향 | GitOps 관리? |
|---|---|---|---|---|---|
| 1 | `GITOPS_PAT` | GitHub Actions secret | 90일(PAT 만료) | main에 임의 커밋 푸시 → Flux가 배포 | ❌ (GitHub) |
| 2 | `OCIR_AUTH_TOKEN` | GitHub Actions secret + Vault→`ocir-pull-secret` | 180일 | 레지스트리 이미지 push/pull | 일부(ExternalSecret) |
| 3 | `zerossl-eab-hmac` | cluster Secret `cert-manager` ns (out-of-band) | 클러스터 재구축 시 | 낮음(ACME 계정 바인딩만) | ❌ (수동) |
| 4 | OCI Vault 접근(ESO) | ClusterSecretStore `oci-vault` 인증(인스턴스 프린시펄/API키) | 180일 or IAM 정책 | **최상위** — 모든 하위 시크릿 접근 | ❌ (OCI IAM) |
| 5 | Flux git deploy key | `flux-system` Secret (SSH) | 유출 시 즉시 | 저장소 read (읽기 전용 권장) | flux bootstrap |
| 6 | ATP DB 자격증명 | Vault→`backend-springboot-secrets` (1h refresh) | 90일 | DB 접근 | ExternalSecret |
| 7 | `ANTHROPIC_API_KEY` (예정) | Vault→backend env | 90일 | AI 호출 과금·데이터 | ExternalSecret |
| 8 | SMTP 자격증명 (예정) | Vault→backend env | 90일 | 이메일 발송 도용 | ExternalSecret |

## 공통 원칙

- **Vault 경유 시크릿(2,6,7,8)**: 로테이션은 항상 **Vault 값 변경 → ExternalSecret이 refresh 주기 내 자동 전파**. 클러스터 Secret을 직접 편집하지 말 것(다음 refresh에 덮어씌워짐). 즉시 반영이 필요하면 `kubectl annotate externalsecret <name> -n <ns> force-sync=$(date +%s)`.
- **out-of-band 시크릿(3,4,5)**: Git·Vault 밖 수동 자산. 클러스터/계정 재구축 runbook에 재생성 단계 필수.
- 로테이션 후 반드시 **구 자격증명 폐기(revoke)** — 신 자격증명 발급만으로는 유출 창이 닫히지 않는다.

## 절차 (비자명한 것만)

### 1. GITOPS_PAT (CI가 main에 직푸시하는 owner PAT)
1. GitHub → Settings → Developer settings → Fine-grained tokens → 새 토큰(Only `ai-automated-enterprise`, Contents: Read and write, 만료 90일).
2. 저장소 → Settings → Secrets → Actions → `GITOPS_PAT` 값 교체.
3. **구 토큰 Revoke** (Developer settings에서 삭제).
4. 검증: 앱 소스에 사소한 변경 push → CI가 GitOps 태그 커밋을 main에 push 성공하는지 확인(`main-pr-gate` bypass 동작). 실패 시 폴백은 `${{ secrets.GITOPS_PAT || github.token }}` — 파이프라인은 깨지지 않으나 게이트 우회가 안 됨.
- **탐지**: GitHub 감사 로그의 예상치 못한 push, Flux가 반영한 미승인 매니페스트.

### 4. OCI Vault 접근 (최상위 신뢰 루트 — 최우선)
External Secrets Operator가 Vault를 읽는 자격증명. 유출 시 하위 전부(DB, OCIR, API키) 노출.
1. OCI Console → IAM → ESO가 쓰는 사용자/동적그룹의 API 키 로테이션(또는 인스턴스 프린시펄 정책 재바인딩).
2. ESO 인증 Secret 갱신(`gitops/security/external-secrets/` ClusterSecretStore 참조 Secret).
3. 검증: `kubectl get clustersecretstore oci-vault -o jsonpath='{.status.conditions}'` → Ready, ExternalSecret들 `SecretSynced`.
- **탐지**: OCI 감사 로그의 비정상 Vault GetSecretBundle 호출.

### 6/7/8. Vault 경유 앱 시크릿
1. 새 값 준비(ATP: 콘솔에서 비번 변경 — Terraform `ignore_changes=[admin_password]`라 apply가 되돌리지 않음 / Anthropic: 콘솔에서 새 키 / SMTP: provider에서 새 자격증명).
2. OCI Vault Secret 새 버전 생성.
3. `kubectl annotate externalsecret <name> -n backend force-sync=$(date +%s)`로 즉시 전파(또는 refresh 대기).
4. 백엔드 롤링 재시작으로 새 env 반영: `kubectl rollout restart deploy -n backend backend-springboot`(Flagger 카나리를 통해). ATP는 `initialization-fail-timeout: -1`이라 전파 지연 중에도 파드는 죽지 않고 `/api/status`가 DOWN 보고.
5. 구 값 폐기.

### 3. ZeroSSL EAB (클러스터 재구축 시에만)
EAB는 **1회용**. 재사용 불가 — 클러스터를 새로 만들면 ZeroSSL Developer 섹션에서 새 EAB(Key ID + HMAC) 발급 후 `kubectl create secret generic zerossl-eab-hmac -n cert-manager --from-literal=secret=<HMAC>`. Key ID는 비밀 아님(매니페스트에 그대로).

## 유출 대응 (공통)
1. 즉시 구 자격증명 **revoke**(신 발급 전이라도).
2. blast radius 표로 영향 범위 산정.
3. 해당 감사 로그(GitHub / OCI)에서 오용 흔적 확인.
4. 최상위(#4) 유출 시: 하위 전부(#2,6,7,8) 연쇄 로테이션.
5. 사후: BACKLOG 0절 알림이 가동되면 `vault_credential.refresh_failed` 등에 알림 연결(현재는 수동 감시).
