# Discord 알림 운영 Runbook

## 목적과 안전 원칙

Flux, Flagger, Alertmanager의 사람이 조치해야 하는 오류를 하나의 Discord 운영 채널로 전달한다. 실제 Webhook 값은 Git 또는 Kubernetes 매니페스트에 기록하지 않는다. 모든 클러스터 변경은 `gitops/`의 Git 커밋과 Flux 조정으로만 수행하며, 이 절차는 프로덕션에 고의 장애를 만들지 않는다.

필요한 자격증명은 Discord Bot API 키가 아니라 운영 채널에서 발급한 **incoming Webhook URL 한 개**다.

## 최초 프로비저닝

1. Discord 운영 채널의 **Integrations → Webhooks**에서 incoming Webhook 하나를 생성하고 base URL을 복사한다.
2. OCI Vault에 `DISCORD_ALERTS_WEBHOOK_URL`이라는 이름으로 base URL 전체를 저장한다. 저장소나 셸 기록에 값을 남기지 않는다.
3. Vault 값에는 `/github`를 붙이지 않는다. Flux, Flagger, Alertmanager는 Discord가 발급한 base URL을 그대로 사용한다.
4. External Secrets Operator가 다음 세 Kubernetes Secret을 만들 때까지 Flux 상태와 ExternalSecret의 `Ready` 조건을 확인한다.

   - `flux-system/flux-discord-webhook`
   - `backend/flagger-discord-webhook`
   - `monitoring/alertmanager-discord-webhook`

다음 명령은 상태만 읽으며 Secret 값을 출력하지 않는다.

```powershell
kubectl -n flux-system get externalsecret flux-discord-webhook
kubectl -n backend get externalsecret flagger-discord-webhook
kubectl -n monitoring get externalsecret alertmanager-discord-webhook
kubectl -n flux-system get secret flux-discord-webhook
kubectl -n backend get secret flagger-discord-webhook
kubectl -n monitoring get secret alertmanager-discord-webhook
```

어느 ExternalSecret이든 `Ready=True`가 아니면 평문 기본값을 만들지 않는다. OCI Vault 키 이름, OCI IAM 권한, `ClusterSecretStore/oci-vault` 상태부터 복구한다.

## GitHub → Discord 외부 설정

이 단계는 GitOps 범위 밖의 저장소 설정이므로 운영자가 직접 완료해야 한다.

1. GitHub 저장소의 **Settings → Webhooks → Add webhook**으로 이동한다.
2. Payload URL에는 Discord base URL 뒤에 `/github`를 붙인 값을 입력한다. OCI Vault의 값 자체는 변경하지 않는다.
3. Content type은 `application/json`으로 설정한다.
4. 전체 이벤트를 보내지 말고 Discord `/github` 엔드포인트가 공식 지원하는 다음 이벤트만 선택한다.

   - `check_run`, `check_suite` — GitHub Actions/CI 성공·실패 상태
   - `issues`, `issue_comment` — ZAP 등이 만든 이슈와 후속 조치
   - `pull_request`, `pull_request_review`, `pull_request_review_comment` — 일반 PR과 Dependabot 보안 업데이트 PR
   - `release`

`workflow_run` and `dependabot_alert` are not supported by Discord `/github` and must not be selected. 워크플로 실패는 지원되는 `check_run`/`check_suite`로 관찰하고, Dependabot이 만든 PR은 `pull_request`와 check 이벤트로 관찰한다. PR을 만들지 않는 Dependabot alert의 직접 전달은 별도 전달 경로가 승인되기 전까지 후속 작업이다.

5. GitHub의 Recent Deliveries에서 정상 응답을 확인한다. 실제 CI 실패나 프로덕션 장애를 인위적으로 만들지 않는다.

## 배포 후 읽기 전용 확인

다음 리소스의 존재와 상태를 확인한다.

```powershell
kubectl -n flux-system get provider discord
kubectl -n flux-system get alert platform-errors
kubectl -n backend get alertprovider discord
kubectl -n monitoring get alertmanagerconfig platform-alertmanager
kubectl -n monitoring get alertmanager kube-prometheus-stack-alertmanager
kubectl -n monitoring get pods -l alertmanager=kube-prometheus-stack-alertmanager
kubectl -n monitoring get prometheusrule platform-actionable-alerts
kubectl -n cert-manager get deployment/cert-manager -o wide
kubectl -n cert-manager get pods -l app.kubernetes.io/component=controller -o wide
kubectl -n flux-system get deployment/notification-controller -o wide
kubectl -n flux-system get pods -l app=notification-controller -o wide
```

- Flux `Provider/discord`와 `Alert/platform-errors`가 오류를 보이지 않는지 확인한다.
- Flagger `AlertProvider/discord`가 Secret을 참조하는지 확인한다.
- `AlertmanagerConfig/platform-alertmanager`의 Discord `apiURL`이 `alertmanager-discord-webhook/address`를 참조하는지 확인한다. Secret 값 자체를 출력하지 않는다.
- Alertmanager 조건이 `Reconciled=True`, `Available=True`이고 Pod가 정확히 1개만 Ready인지 확인한다. PrometheusRule에는 `NodeCPURequestsHigh`, `CertificateExpiringSoon`, `FlaggerCanaryFailed`가 로드돼야 한다.
- 경보 전달 검증은 자연 발생한 비프로덕션 또는 실제 이벤트를 사용한다. 확인만을 위해 프로덕션 Kustomization, Canary, Pod를 고의로 실패시키지 않는다.
- Alertmanager 경로는 2026-07-18 실제 경고의 Discord 수신으로 검증했다. Flux Provider와 Flagger AlertProvider의 직접 전달은 각각 자연 이벤트를 확인할 때까지 별도 대기 상태다.
- 전달이 안 되면 ExternalSecret → Provider/AlertProvider/Alertmanager 상태 → DNS → Cilium/Hubble drop 순서로 확인한다.

## Discord 라우팅 및 복구 판정

- warning은 `namespace`별로 `groupWait: 2m`, `groupInterval: 5m`, `repeatInterval: 12h`를 적용한다. 같은 컴포넌트의 CrashLoop, replica mismatch, target down을 한 사고 묶음으로 모은다.
- critical은 `groupWait: 30s`, `repeatInterval: 4h`를 유지한다. 새 critical을 누락하지 않도록 Discord가 기본 receiver다.
- `severity=info`는 `null`로 보낸다. `KubeCPUOvercommit`과 `KubeMemoryOvercommit`도 이 2노드 Free Tier의 구조적 무중단 조건 경고이므로 `null`로 보내되 Prometheus/Grafana에는 보존한다. 실제 포화는 `NodeCPURequestsHigh`로 감시한다.
- OKE의 Kubernetes Service 443은 현재 Cilium에서 `10.0.0.73:6443` API backend로 변환된다. cert-manager와 notification-controller의 전용 CNP는 `kube-apiserver` identity에 TCP 443과 6443만 허용해야 한다.
- `[RESOLVED]` 한 건만으로 복구를 선언하지 않는다. `stable for 10m`: 최소 10분 동안 Deployment와 Pod가 Ready이고 `restart` 횟수가 증가하지 않으며 Prometheus에 해당 alert의 `pending` 및 `firing` 상태가 모두 없어야 한다.
- cert-manager는 readiness probe가 없어 CrashLoop 재시작 직후 짧은 실행 구간이 Ready로 샘플링될 수 있다. 이때 replica mismatch가 RESOLVED 후 다시 pending으로 바뀌는 것은 복구가 아니라 플랩이다.

Secret 값을 출력하지 않고 Prometheus의 `ALERTS` 시계열, Deployment Available 수, Pod Ready 상태와 restart 횟수를 함께 확인한다. 정책 변경 후에도 이 네 조건 중 하나가 실패하면 포트를 추가로 넓히지 말고 Cilium drop과 컨트롤러 로그부터 다시 진단한다.

## Webhook 교체(rotation)

1. Discord에 새 incoming Webhook을 생성하되 기존 Webhook은 유지한다.
2. OCI Vault의 `DISCORD_ALERTS_WEBHOOK_URL` 값을 새 base URL로 교체한다.
3. 세 ExternalSecret이 다시 `Ready=True`가 되고 세 Secret의 `resourceVersion`이 갱신됐는지 확인한다. Secret 데이터는 출력하지 않는다.
4. GitHub Settings의 Payload URL도 새 base URL에 `/github`를 붙인 값으로 교체한다.
5. Provider, AlertProvider, Alertmanager와 GitHub Recent Deliveries의 정상 상태를 확인한다.
6. 모든 소비자가 새 URL을 사용하는 것이 확인된 뒤에만 Discord에서 기존 Webhook을 제거한다.

교체 과정에서도 매니페스트에 임시 URL을 넣거나 수동 `kubectl apply`로 Secret을 만들지 않는다.
