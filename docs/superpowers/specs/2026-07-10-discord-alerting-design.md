# Discord 기반 P1~P2 알림 설계

## 상태

- 설계 승인일: 2026-07-10
- 기준 브랜치: `origin/main` (`63115be`)
- 구현 브랜치: `codex/discord-alerting-p1-p2`
- 범위: 현재 자격증명 없이 선언적으로 구현할 수 있는 P1~P2 알림 경로

## 목표

1. Flux의 Kustomization/HelmRelease 오류를 Discord로 통지한다.
2. Flagger 카나리 실패와 롤백을 Discord로 통지한다.
3. 최소 Alertmanager 한 개를 활성화하여 런타임 경보를 Discord로 통지한다.
4. 새 외부 통신은 CiliumNetworkPolicy로 DNS와 `discord.com:443`만 명시적으로 허용한다.
5. 평문 비밀값, 수동 프로덕션 `kubectl apply`, 신규 상주 릴레이 서비스를 사용하지 않는다.

## 비목표와 후속 범위

- SMTP 자격증명이 없으므로 이메일 라우팅은 이번 변경에서 제외한다.
- GitHub 저장소의 Discord Webhook 등록은 저장소 밖의 일회성 설정이므로 runbook으로 제공하되 자동 실행하지 않는다.
- ZAP active scan, node drain, DB/메시 장애훈련, 스트레스 테스트, 월간 게임데이는 운영 영향과 별도 승인 절차가 필요하므로 후속 작업으로 남긴다.
- Testcontainers 추가, GitOps 태그 push 경합 완화 등 알림과 독립적인 개발·파이프라인 P1~P2는 각각 별도 설계와 구현 계획으로 진행한다.
- Actuator 외부 차단, Flannel 중화, Dependabot 구성처럼 코드상 이미 완료된 항목은 중복 구현하지 않는다. 백로그 문구는 실제 상태에 맞춰 별도 정리할 수 있다.

## 선택한 구조

Flux, Flagger, Alertmanager가 각자의 네이티브 Discord 연동을 직접 사용한다. 중앙 알림 릴레이는 배포하지 않는다. 이 방식은 기존 컨트롤러 외에 상주 Pod를 추가하지 않으며, Alertmanager 한 개만 새로 기동한다.

세 연동은 OCI Vault의 동일한 비밀값을 네임스페이스별 ExternalSecret으로 읽는다. Kubernetes Secret은 네임스페이스 범위를 벗어나 공유할 수 없으므로 세 개의 로컬 Secret을 만들되, 원격 Vault 키는 하나만 관리한다.

```text
OCI Vault: DISCORD_ALERTS_WEBHOOK_URL
  ├─ flux-system/flux-discord-webhook[address] ──> Flux Provider
  ├─ backend/flagger-discord-webhook[address] ───> Flagger AlertProvider
  └─ monitoring/alertmanager-discord-webhook[address] ──> Alertmanager file mount
```

필요한 Discord 자격증명은 Bot API 키가 아니라 채널에서 발급한 Webhook URL 한 개다. URL 값은 Git이나 매니페스트에 기록하지 않는다.

## Flux 알림

`gitops/infrastructure/notifications/` 레이어에 다음 리소스를 둔다.

- `ExternalSecret`: `DISCORD_ALERTS_WEBHOOK_URL`을 `flux-discord-webhook` Secret의 `address` 키로 동기화한다.
- `Provider`: `notification.toolkit.fluxcd.io/v1beta3`, 유형 `discord`, 위 Secret을 참조한다.
- `Alert`: `flux-system`의 모든 Kustomization과 HelmRelease에서 발생한 `error` 이벤트만 전송한다.

새 Flux Kustomization은 인프라 직렬 체인의 마지막에서 `infra-flagger`에 의존한다. 이 레이어가 실패해도 이미 실행 중인 애플리케이션의 데이터 경로는 변경되지 않는다.

현재 bootstrap의 `allow-egress` NetworkPolicy는 `flux-system` 전체에 무제한 egress를 허용하므로 그대로 두면 Discord CNP가 무의미하다. 생성된 `gotk-components.yaml`은 직접 수정하지 않고, `gitops/clusters/production/flux-system/kustomization.yaml`에서 `notification-controller`를 해당 무제한 정책 대상에서 제외한다. 같은 bootstrap Kustomization에 전용 CNP를 포함하여 다음만 허용한다.

- CoreDNS TCP/UDP 53
- Kubernetes API TCP 443
- `discord.com` TCP 443

기존 webhook ingress 정책은 수신 정책이므로 Discord outbound 허용으로 간주하지 않는다.

## Flagger 카나리 알림

`backend` 네임스페이스에 별도의 ExternalSecret과 `AlertProvider`를 추가한다. 기존 Canary의 `analysis.alerts`에는 `severity: error`인 Discord provider 참조를 추가하여 실패·롤백 이벤트만 통지하고 정상 분석 단계의 소음은 보내지 않는다.

Flagger 컨트롤러의 egress 정책은 기존 동작과 새 Discord 통신을 모두 보존하도록 다음 목적지만 허용한다.

- CoreDNS
- Kubernetes API
- `monitoring`의 Prometheus TCP 9090
- `flagger-system`의 loadtester
- `discord.com` TCP 443

loadtester도 별도 정책으로 Flagger의 webhook 요청만 수신하고, 기존 합성 트래픽 목적지인 `api.ai-auto.kro.kr:443`만 송신하도록 제한한다. 이 작업은 알림을 추가하면서 기존 무제한 egress를 방치하지 않기 위한 Zero-Trust 보완이다.

## Alertmanager와 경보 규칙

기존 kube-prometheus-stack 75.x의 Alertmanager를 다음과 같이 최소 구성으로 활성화한다.

- replica: 1
- 데이터 보존: 24시간
- 영구 볼륨: 없음
- request: CPU `10m`, memory `32Mi`
- limit: memory `128Mi`
- 기본 receiver: Discord
- group wait: 30초
- group interval: 5분
- repeat interval: 4시간
- 해결 알림 전송: 활성화

Webhook URL은 `alertmanager.alertmanagerSpec.secrets`를 통해 파일로 마운트하고 `discord_configs.webhook_url_file`로 읽는다. Helm values 또는 Alertmanager 설정에 URL을 직접 넣지 않는다.

Alertmanager Pod의 CNP는 Prometheus로부터 TCP 9093 ingress를 허용하고, egress는 CoreDNS와 `discord.com:443`만 허용한다. 단일 replica이므로 Alertmanager gossip용 외부 peer egress는 열지 않는다.

경보 규칙은 다음과 같다.

1. `KubePodCrashLooping`: kube-prometheus-stack의 기존 기본 규칙을 재사용하며 중복 정의하지 않는다.
2. 노드 CPU requests 비율: kube-state-metrics의 Pod 요청량을 노드별 allocatable CPU로 나눈 값이 95%를 10분간 초과하면 경보한다.
3. 인증서 만료: `certmanager_certificate_expiration_timestamp_seconds - time()`이 21일 미만인 상태가 15분 지속되면 경보한다.
4. Flagger 실패: 공식 메트릭 기준 `flagger_canary_status > 1`이 2분 지속되면 경보한다.

모든 사용자 정의 규칙에는 요약, 영향, 확인할 리소스를 annotation으로 제공한다. Discord 채널에는 사람이 조치해야 하는 이벤트만 전달하며 로그 전량은 보내지 않는다.

Flagger native 알림과 Alertmanager의 Flagger 규칙은 의도적으로 역할이 다르다. native 알림은 롤백 이벤트를 즉시 알리고, Prometheus 규칙은 실패 상태가 2분 이상 남아 있는 경우에만 지속 상태를 경고한다. Alertmanager의 4시간 repeat interval로 동일 실패의 반복 소음을 제한한다.

## 실패 처리와 배포 순서

1. 사용자가 OCI Vault에 `DISCORD_ALERTS_WEBHOOK_URL`을 먼저 생성한다.
2. Flux bootstrap이 notification-controller의 무제한 egress 제외와 전용 CNP를 함께 적용한다.
3. External Secrets Operator가 세 네임스페이스의 로컬 Secret을 생성한다.
4. Flux Provider/Alert, Flagger AlertProvider, Alertmanager가 Secret을 소비한다.
5. GitHub→Discord 연결은 runbook에 따라 동일 Webhook의 GitHub 호환 endpoint를 저장소 설정에 등록한다.

Vault 키가 없으면 평문 기본값으로 대체하지 않고 ExternalSecret이 Ready가 되지 않도록 실패 폐쇄한다. 특히 Alertmanager HelmRelease는 Secret을 마운트해야 하므로 배포 전 Vault 등록이 필수다. 기존 애플리케이션 요청 경로에는 Discord 의존성을 추가하지 않는다.

Discord가 일시적으로 응답하지 않으면 각 네이티브 컴포넌트의 재시도와 backoff에 맡기며, 전달 실패 때문에 카나리 제어·Prometheus 수집·애플리케이션 요청을 중단하지 않는다. 운영자는 provider/Alertmanager 상태와 Hubble의 drop 기록으로 Secret 오류, DNS 오류, CNP 차단을 구분한다.

## 검증 전략

구성 변경에도 Red-Green 흐름을 적용한다.

1. 먼저 GitOps 알림 계약 검증 스크립트를 추가한다. 이 스크립트는 알림 리소스, Vault remote key, Alertmanager 최소 리소스, 경보식, Discord FQDN egress, Flux 무제한 egress 제외를 요구한다.
2. 구현 전 스크립트를 실행하여 필요한 매니페스트가 없어 실패하는 것을 확인한다.
3. 최소 매니페스트를 추가한 뒤 스크립트와 모든 관련 `kubectl kustomize` 렌더링을 통과시킨다. 클러스터에 apply하지 않는다.
4. 프론트엔드 `npm test`, 백엔드 `mvnw.cmd test`를 다시 실행하여 기존 파이프라인 회귀가 없음을 확인한다.
5. diff에 Discord Webhook 형태의 문자열이나 기타 비밀값이 없는지 검사한다.

실제 Discord 전달은 Vault 값과 운영 클러스터가 필요한 통합 검증이므로 배포 후 runbook의 테스트 이벤트 절차로 확인한다. 테스트를 위해 오류 상태를 프로덕션에 강제로 만들지는 않는다.

## 문서화와 백로그 상태

`REQUIRED_SECRETS.md`와 Discord runbook에 정확한 Vault 키 이름, 값의 형식, GitHub 외부 설정, 안전한 확인 절차를 기록한다. 이번 변경이 병합되어도 사용자가 Vault 값을 넣고 실제 알림을 확인하기 전에는 외부 연동 백로그를 완전 완료로 표시하지 않는다. 이메일 라우팅과 운영 드릴은 후속 항목으로 명시적으로 남긴다.
