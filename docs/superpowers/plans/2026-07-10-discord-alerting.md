# Discord Alerting P1~P2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Deliver Flux, Flagger, and minimal Alertmanager Discord notifications with namespace-scoped ExternalSecrets, actionable runtime rules, and explicit Cilium egress allowlists.

**Architecture:** Each existing controller uses its native Discord integration and reads one OCI Vault value through a namespace-local ExternalSecret. No relay service is added. Offline PowerShell contract tests render every changed Kustomization with \`kubectl kustomize\` and assert security, secret, resource, and alerting contracts before CI runs Trivy.

**Tech Stack:** Flux notification-controller v2.9 CRDs, External Secrets Operator v1, Flagger 1.43, kube-prometheus-stack 75.18.1 / Alertmanager 0.28.1, CiliumNetworkPolicy v2, PowerShell 7, kubectl kustomize.

## Global Constraints

- There is no implicit network trust; every new Discord egress is restricted to CoreDNS and \`discord.com:443\`.
- Kubernetes state changes only through committed files under \`gitops/\`; never run a production \`kubectl apply\`.
- Never commit the Discord Webhook URL. The only remote key name is \`DISCORD_ALERTS_WEBHOOK_URL\`.
- Alertmanager is one replica with CPU request \`10m\`, memory request \`32Mi\`, memory limit \`128Mi\`, no PVC, and 24-hour retention.
- Preserve user-owned work and generated \`gotk-components.yaml\`; patch generated resources through Kustomize.
- Apply Red-Green TDD to each configuration slice by writing its contract script first and observing the expected missing-feature failure.

---

### Task 1: Flux Discord notifications and constrained egress

**Files:**
- Create: \`scripts/gitops-test-helpers.ps1\`
- Create: \`scripts/test-flux-notifications.ps1\`
- Create: \`gitops/clusters/production/flux-system/notification-egress.yaml\`
- Modify: \`gitops/clusters/production/flux-system/kustomization.yaml\`
- Create: \`gitops/infrastructure/notifications/kustomization.yaml\`
- Create: \`gitops/infrastructure/notifications/externalsecret.yaml\`
- Create: \`gitops/infrastructure/notifications/provider.yaml\`
- Create: \`gitops/infrastructure/notifications/alert.yaml\`
- Modify: \`gitops/clusters/production/infrastructure.yaml\`

**Interfaces:**
- Consumes: \`ClusterSecretStore/oci-vault\`, Flux Provider and Alert CRDs, CoreDNS, Kubernetes API.
- Produces: \`Secret/flux-discord-webhook[address]\`, \`Provider/discord\`, \`Alert/platform-errors\`, and an isolated notification-controller egress policy.

- [ ] **Step 1: Add the shared offline test helpers**

\`\`\`powershell
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Read-RepoFile {
    param([Parameter(Mandatory)][string]$RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "[MISSING] $RelativePath"
    }
    Get-Content -Raw -Encoding UTF8 -LiteralPath $path
}

function Build-Kustomization {
    param([Parameter(Mandatory)][string]$RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "[MISSING] $RelativePath"
    }
    $rendered = & kubectl kustomize $path 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("[KUSTOMIZE] " + $RelativePath + [Environment]::NewLine + ($rendered -join [Environment]::NewLine))
    }
    $rendered -join [Environment]::NewLine
}

function Assert-Matches {
    param(
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string]$Because
    )
    if ($Actual -notmatch $Pattern) {
        throw "[ASSERT] $Because (pattern: $Pattern)"
    }
}

function Assert-NoMatches {
    param(
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string]$Because
    )
    if ($Actual -match $Pattern) {
        throw "[ASSERT] $Because (forbidden pattern: $Pattern)"
    }
}
\`\`\`

- [ ] **Step 2: Write the Flux contract test**

\`\`\`powershell
. (Join-Path $PSScriptRoot "gitops-test-helpers.ps1")

$notifications = Build-Kustomization "gitops/infrastructure/notifications"
$fluxBootstrap = Build-Kustomization "gitops/clusters/production/flux-system"
$infrastructure = Read-RepoFile "gitops/clusters/production/infrastructure.yaml"

Assert-Matches $notifications "(?m)^kind: ExternalSecret$" "Flux Discord credential must come from ExternalSecret"
Assert-Matches $notifications "(?m)^\s+key: DISCORD_ALERTS_WEBHOOK_URL$" "Flux must use the approved OCI Vault key"
Assert-Matches $notifications "(?m)^kind: Provider$" "Flux Discord Provider must exist"
Assert-Matches $notifications "(?m)^\s+type: discord$" "Flux Provider must use Discord"
Assert-Matches $notifications "(?m)^kind: Alert$" "Flux Alert must exist"
Assert-Matches $notifications "(?m)^\s+eventSeverity: error$" "Only actionable Flux errors should notify"
Assert-Matches $notifications "(?ms)eventSources:.*?kind: Kustomization.*?kind: HelmRelease" "Both reconciliation kinds must be covered"
Assert-Matches $fluxBootstrap "(?m)^kind: CiliumNetworkPolicy$" "notification-controller needs a Cilium policy"
Assert-Matches $fluxBootstrap "(?m)^\s+- matchName: discord\.com$" "Discord egress must be FQDN-scoped"
Assert-Matches $fluxBootstrap "(?m)^\s+- kube-apiserver$" "notification-controller needs Kubernetes API"
Assert-Matches $fluxBootstrap "(?ms)kind: NetworkPolicy.*?name: allow-egress.*?podSelector:\s+matchExpressions:.*?operator: NotIn.*?notification-controller" "notification-controller must leave broad egress"
Assert-Matches $infrastructure "(?m)^\s+name: infra-notifications$" "notifications need a Flux Kustomization"
Assert-Matches $infrastructure "(?ms)name: infra-notifications.*?dependsOn:.*?name: infra-flagger.*?name: security-external-secrets" "notifications must wait for prerequisites"

Write-Host "Flux notification contracts passed"
\`\`\`

- [ ] **Step 3: Run RED**

Run \`pwsh -NoProfile -File scripts/test-flux-notifications.ps1\`.

Expected: FAIL with \`[MISSING] gitops/infrastructure/notifications\`.

- [ ] **Step 4: Implement the Flux notification layer**

Create the Kustomization referencing \`externalsecret.yaml\`, \`provider.yaml\`, and \`alert.yaml\`. The three resources must be:

\`\`\`yaml
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: flux-discord-webhook
  namespace: flux-system
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: ClusterSecretStore
    name: oci-vault
  target:
    name: flux-discord-webhook
    creationPolicy: Owner
  data:
    - secretKey: address
      remoteRef:
        key: DISCORD_ALERTS_WEBHOOK_URL
---
apiVersion: notification.toolkit.fluxcd.io/v1beta3
kind: Provider
metadata:
  name: discord
  namespace: flux-system
spec:
  type: discord
  secretRef:
    name: flux-discord-webhook
---
apiVersion: notification.toolkit.fluxcd.io/v1beta3
kind: Alert
metadata:
  name: platform-errors
  namespace: flux-system
spec:
  providerRef:
    name: discord
  eventSeverity: error
  eventMetadata:
    environment: production
  eventSources:
    - kind: Kustomization
      name: "*"
    - kind: HelmRelease
      name: "*"
\`\`\`

- [ ] **Step 5: Isolate notification-controller egress**

Create \`notification-egress.yaml\` and add it to the bootstrap Kustomization:

\`\`\`yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: notification-controller-discord-egress
  namespace: flux-system
spec:
  endpointSelector:
    matchLabels:
      app: notification-controller
  egress:
    - toEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: kube-system
            k8s-app: kube-dns
      toPorts:
        - ports:
            - port: "53"
              protocol: UDP
            - port: "53"
              protocol: TCP
          rules:
            dns:
              - matchPattern: "*"
    - toEntities:
        - kube-apiserver
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
    - toFQDNs:
        - matchName: discord.com
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
\`\`\`

Patch \`NetworkPolicy/allow-egress\` there, without changing generated YAML:

\`\`\`yaml
- target:
    kind: NetworkPolicy
    name: allow-egress
  patch: |-
    - op: replace
      path: /spec/podSelector
      value:
        matchExpressions:
          - key: app
            operator: NotIn
            values:
              - notification-controller
\`\`\`

- [ ] **Step 6: Add the reconciled infrastructure layer**

Append a Kustomization named \`infra-notifications\`, depending on \`infra-flagger\` and \`security-external-secrets\`, with path \`./gitops/infrastructure/notifications\`, \`wait: true\`, \`prune: true\`, interval 10m, retry 2m, timeout 5m.

- [ ] **Step 7: Run GREEN**

\`\`\`powershell
pwsh -NoProfile -File scripts/test-flux-notifications.ps1
kubectl kustomize gitops/clusters/production/flux-system
kubectl kustomize gitops/infrastructure/notifications
\`\`\`

Expected: exit 0 and \`Flux notification contracts passed\`.

- [ ] **Step 8: Commit**

\`\`\`powershell
git add scripts/gitops-test-helpers.ps1 scripts/test-flux-notifications.ps1 gitops/clusters/production/flux-system gitops/infrastructure/notifications gitops/clusters/production/infrastructure.yaml
git commit -m "feat(gitops): add Flux Discord error notifications"
\`\`\`

---

### Task 2: Flagger failure notifications and egress isolation

**Files:**
- Create: \`scripts/test-flagger-alerting.ps1\`
- Create: \`gitops/apps/backend-springboot/progressive-delivery/discord-externalsecret.yaml\`
- Create: \`gitops/apps/backend-springboot/progressive-delivery/alert-provider.yaml\`
- Modify: \`gitops/apps/backend-springboot/progressive-delivery/canary.yaml\`
- Modify: \`gitops/apps/backend-springboot/kustomization.yaml\`
- Modify: \`gitops/infrastructure/flagger/release.yaml\`
- Create: \`gitops/infrastructure/flagger/network-policy.yaml\`
- Modify: \`gitops/infrastructure/flagger/kustomization.yaml\`

**Interfaces:**
- Consumes: \`DISCORD_ALERTS_WEBHOOK_URL\`, Prometheus, Kubernetes API, and loadtester.
- Produces: \`Secret/flagger-discord-webhook[address]\`, \`AlertProvider/discord\`, Canary error alerts, a Flagger ServiceMonitor, and two CNPs.

- [ ] **Step 1: Write the Flagger contract test**

\`\`\`powershell
. (Join-Path $PSScriptRoot "gitops-test-helpers.ps1")

$backend = Build-Kustomization "gitops/apps/backend-springboot"
$flagger = Build-Kustomization "gitops/infrastructure/flagger"

Assert-Matches $backend "(?m)^\s+key: DISCORD_ALERTS_WEBHOOK_URL$" "Flagger must use the approved OCI Vault key"
Assert-Matches $backend "(?m)^kind: AlertProvider$" "Flagger AlertProvider must exist"
Assert-Matches $backend "(?m)^\s+type: discord$" "Flagger AlertProvider must use Discord"
Assert-Matches $backend "(?ms)alerts:.*?severity: error.*?providerRef:.*?name: discord" "Canary must emit only error alerts"
Assert-Matches $flagger "(?ms)serviceMonitor:.*?enabled: true.*?release: kube-prometheus-stack" "Flagger metrics must be selected"
Assert-Matches $flagger "(?m)^kind: CiliumNetworkPolicy$" "Flagger workloads must have Cilium policies"
Assert-Matches $flagger "(?m)^\s+- matchName: discord\.com$" "Flagger Discord egress must be FQDN-scoped"
Assert-Matches $flagger "(?m)^\s+- matchName: api\.ai-auto\.kro\.kr$" "loadtester egress must remain scoped"
Assert-Matches $flagger "(?m)^\s+- kube-apiserver$" "Flagger must retain API access"
Assert-Matches $flagger "(?m)^\s+- port: ""9090""$" "Flagger must retain Prometheus access"
Assert-Matches $flagger "(?m)^\s+- port: ""8080""$" "metrics and loadtester ports must be explicit"

Write-Host "Flagger alerting contracts passed"
\`\`\`

- [ ] **Step 2: Run RED**

Run \`pwsh -NoProfile -File scripts/test-flagger-alerting.ps1\`.

Expected: FAIL because \`AlertProvider\` and the CNPs do not exist.

- [ ] **Step 3: Add the backend secret and provider**

\`discord-externalsecret.yaml\`:

\`\`\`yaml
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: flagger-discord-webhook
  namespace: backend
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: ClusterSecretStore
    name: oci-vault
  target:
    name: flagger-discord-webhook
    creationPolicy: Owner
  data:
    - secretKey: address
      remoteRef:
        key: DISCORD_ALERTS_WEBHOOK_URL
\`\`\`

\`alert-provider.yaml\`:

\`\`\`yaml
apiVersion: flagger.app/v1beta1
kind: AlertProvider
metadata:
  name: discord
  namespace: backend
spec:
  type: discord
  secretRef:
    name: flagger-discord-webhook
\`\`\`

Add both files to the backend Kustomization. Add this under \`spec.analysis\`:

\`\`\`yaml
alerts:
  - name: backend-canary-failed
    severity: error
    providerRef:
      name: discord
\`\`\`

- [ ] **Step 4: Enable Flagger metric discovery**

Add under the Flagger HelmRelease values:

\`\`\`yaml
serviceMonitor:
  enabled: true
  labels:
    release: kube-prometheus-stack
\`\`\`

- [ ] **Step 5: Add controller and loadtester CNPs**

Create \`network-policy.yaml\` with both documents:

\`\`\`yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: flagger-controller
  namespace: flagger-system
spec:
  endpointSelector:
    matchLabels:
      app.kubernetes.io/name: flagger
      app.kubernetes.io/instance: flagger
  ingress:
    - fromEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: monitoring
            app.kubernetes.io/name: prometheus
            prometheus: kube-prometheus-stack-prometheus
      toPorts:
        - ports:
            - port: "8080"
              protocol: TCP
  egress:
    - toEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: kube-system
            k8s-app: kube-dns
      toPorts:
        - ports:
            - port: "53"
              protocol: UDP
            - port: "53"
              protocol: TCP
          rules:
            dns:
              - matchPattern: "*"
    - toEntities:
        - kube-apiserver
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
    - toEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: monitoring
            app.kubernetes.io/name: prometheus
            prometheus: kube-prometheus-stack-prometheus
      toPorts:
        - ports:
            - port: "9090"
              protocol: TCP
    - toEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: flagger-system
            app: loadtester
      toPorts:
        - ports:
            - port: "8080"
              protocol: TCP
    - toFQDNs:
        - matchName: discord.com
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
---
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: flagger-loadtester
  namespace: flagger-system
spec:
  endpointSelector:
    matchLabels:
      app: loadtester
  ingress:
    - fromEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: flagger-system
            app.kubernetes.io/name: flagger
            app.kubernetes.io/instance: flagger
      toPorts:
        - ports:
            - port: "8080"
              protocol: TCP
  egress:
    - toEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: kube-system
            k8s-app: kube-dns
      toPorts:
        - ports:
            - port: "53"
              protocol: UDP
            - port: "53"
              protocol: TCP
          rules:
            dns:
              - matchPattern: "*"
    - toFQDNs:
        - matchName: api.ai-auto.kro.kr
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
\`\`\`

Add \`network-policy.yaml\` to the Flagger Kustomization.

- [ ] **Step 6: Run GREEN**

\`\`\`powershell
pwsh -NoProfile -File scripts/test-flagger-alerting.ps1
kubectl kustomize gitops/apps/backend-springboot
kubectl kustomize gitops/infrastructure/flagger
\`\`\`

Expected: exit 0 and \`Flagger alerting contracts passed\`.

- [ ] **Step 7: Commit**

\`\`\`powershell
git add scripts/test-flagger-alerting.ps1 gitops/apps/backend-springboot gitops/infrastructure/flagger
git commit -m "feat(gitops): alert on Flagger canary failures"
\`\`\`

---

### Task 3: Minimal Alertmanager and actionable runtime rules

**Files:**
- Create: \`scripts/test-runtime-alerting.ps1\`
- Create: \`gitops/infrastructure/observability/namespace.yaml\`
- Create: \`gitops/infrastructure/observability/discord-externalsecret.yaml\`
- Create: \`gitops/infrastructure/observability/alert-rules.yaml\`
- Create: \`gitops/infrastructure/observability/network-policy.yaml\`
- Modify: \`gitops/infrastructure/observability/kustomization.yaml\`
- Modify: \`gitops/infrastructure/observability/release.yaml\`
- Modify: \`gitops/infrastructure/cert-manager/release.yaml\`
- Modify: \`gitops/clusters/production/infrastructure.yaml\`

**Interfaces:**
- Consumes: cert-manager and Flagger metrics, kube-state-metrics, \`DISCORD_ALERTS_WEBHOOK_URL\`.
- Produces: one Alertmanager, Discord routing, three custom rules, existing KubePodCrashLooping routing, and metric ServiceMonitors.

- [ ] **Step 1: Write the runtime contract test**

\`\`\`powershell
. (Join-Path $PSScriptRoot "gitops-test-helpers.ps1")

$observability = Build-Kustomization "gitops/infrastructure/observability"
$certManager = Build-Kustomization "gitops/infrastructure/cert-manager"
$infrastructure = Read-RepoFile "gitops/clusters/production/infrastructure.yaml"

Assert-Matches $observability "(?m)^kind: Namespace$" "monitoring namespace must exist before secrets"
Assert-Matches $observability "(?m)^\s+name: monitoring$" "Alertmanager resources must target monitoring"
Assert-Matches $observability "(?m)^\s+key: DISCORD_ALERTS_WEBHOOK_URL$" "Alertmanager must use the approved Vault key"
Assert-Matches $observability "(?ms)alertmanager:.*?enabled: true" "Alertmanager must be enabled"
Assert-Matches $observability "(?m)^\s+retention: 24h$" "retention must remain minimal"
Assert-Matches $observability "(?ms)alertmanagerSpec:.*?resources:.*?requests:.*?cpu: 10m.*?memory: 32Mi.*?limits:.*?memory: 128Mi" "Alertmanager must fit the budget"
Assert-Matches $observability "/etc/alertmanager/secrets/alertmanager-discord-webhook/address" "Webhook must be file-backed"
Assert-NoMatches $observability "https://discord\.com/api/webhooks/" "No Webhook URL may be committed"
Assert-Matches $observability "(?m)^\s+- alert: NodeCPURequestsHigh$" "node saturation rule must exist"
Assert-Matches $observability "0\.95" "node CPU threshold must be 95 percent"
Assert-Matches $observability "(?m)^\s+- alert: CertificateExpiringSoon$" "certificate rule must exist"
Assert-Matches $observability "1814400" "certificate window must be 21 days"
Assert-Matches $observability "(?m)^\s+- alert: FlaggerCanaryFailed$" "persistent Flagger rule must exist"
Assert-Matches $observability "flagger_canary_status > 1" "official Flagger failed-state expression must be used"
Assert-Matches $observability "(?m)^\s+release: kube-prometheus-stack$" "rules must match Prometheus selectors"
Assert-Matches $observability "(?m)^kind: CiliumNetworkPolicy$" "Alertmanager needs a Cilium policy"
Assert-Matches $observability "(?m)^\s+- matchName: discord\.com$" "Alertmanager egress must be scoped"
Assert-Matches $observability "(?m)^\s+- port: ""9093""$" "Prometheus must reach Alertmanager"
Assert-Matches $certManager "(?ms)servicemonitor:.*?enabled: true.*?release: kube-prometheus-stack" "cert-manager metrics must be selected"
Assert-Matches $infrastructure "(?ms)name: infra-observability.*?dependsOn:.*?name: infra-istio-ambient.*?name: security-external-secrets" "observability must wait for Vault access"

Write-Host "Runtime alerting contracts passed"
\`\`\`

- [ ] **Step 2: Run RED**

Run \`pwsh -NoProfile -File scripts/test-runtime-alerting.ps1\`.

Expected: FAIL because Alertmanager is disabled and runtime rule files do not exist.

- [ ] **Step 3: Add namespace and ExternalSecret**

\`\`\`yaml
apiVersion: v1
kind: Namespace
metadata:
  name: monitoring
---
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: alertmanager-discord-webhook
  namespace: monitoring
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: ClusterSecretStore
    name: oci-vault
  target:
    name: alertmanager-discord-webhook
    creationPolicy: Owner
  data:
    - secretKey: address
      remoteRef:
        key: DISCORD_ALERTS_WEBHOOK_URL
\`\`\`

- [ ] **Step 4: Configure minimal Alertmanager**

Replace \`alertmanager.enabled: false\` with:

\`\`\`yaml
alertmanager:
  enabled: true
  config:
    global:
      resolve_timeout: 5m
    inhibit_rules:
      - source_matchers: ["severity = critical"]
        target_matchers: ["severity =~ warning|info"]
        equal: [namespace, alertname]
      - source_matchers: ["severity = warning"]
        target_matchers: ["severity = info"]
        equal: [namespace, alertname]
      - source_matchers: ["alertname = InfoInhibitor"]
        target_matchers: ["severity = info"]
        equal: [namespace]
      - target_matchers: ["alertname = InfoInhibitor"]
    route:
      group_by: [namespace, alertname]
      group_wait: 30s
      group_interval: 5m
      repeat_interval: 4h
      receiver: discord
      routes:
        - receiver: "null"
          matchers:
            - 'alertname = "Watchdog"'
    receivers:
      - name: "null"
      - name: discord
        discord_configs:
          - webhook_url_file: /etc/alertmanager/secrets/alertmanager-discord-webhook/address
            send_resolved: true
  alertmanagerSpec:
    replicas: 1
    retention: 24h
    secrets:
      - alertmanager-discord-webhook
    resources:
      requests:
        cpu: 10m
        memory: 32Mi
      limits:
        memory: 128Mi
\`\`\`

- [ ] **Step 5: Add the PrometheusRule**

\`\`\`yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: platform-actionable-alerts
  namespace: monitoring
  labels:
    release: kube-prometheus-stack
spec:
  groups:
    - name: platform-capacity
      rules:
        - alert: NodeCPURequestsHigh
          expr: |
            sum by (node) (kube_pod_container_resource_requests{resource="cpu",unit="core",node!=""})
            /
            sum by (node) (kube_node_status_allocatable{resource="cpu",unit="core"})
            > 0.95
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Node {{ $labels.node }} CPU requests exceed 95%"
            description: "Scheduled CPU requests remain at {{ $value | humanizePercentage }} of allocatable CPU."
            runbook: "Reduce requests or capacity pressure through GitOps before scheduling more workloads."
    - name: platform-certificates
      rules:
        - alert: CertificateExpiringSoon
          expr: |
            (certmanager_certificate_expiration_timestamp_seconds - time() < 1814400)
            and
            (certmanager_certificate_expiration_timestamp_seconds - time() > 0)
          for: 15m
          labels:
            severity: warning
          annotations:
            summary: "Certificate {{ $labels.namespace }}/{{ $labels.name }} expires within 21 days"
            description: "Investigate issuer and renewal status."
            runbook: "Inspect Certificate, CertificateRequest, Challenge, and issuer events."
    - name: platform-flagger
      rules:
        - alert: FlaggerCanaryFailed
          expr: flagger_canary_status > 1
          for: 2m
          labels:
            severity: critical
          annotations:
            summary: "Flagger canary {{ $labels.namespace }}/{{ $labels.name }} is failed"
            description: "Failure persisted after the immediate native Flagger event."
            runbook: "Inspect Canary status and repair with a new Git commit."
\`\`\`

- [ ] **Step 6: Add Alertmanager CNP**

Create \`network-policy.yaml\`:

\`\`\`yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: alertmanager
  namespace: monitoring
spec:
  endpointSelector:
    matchLabels:
      app.kubernetes.io/name: alertmanager
      alertmanager: kube-prometheus-stack-alertmanager
  ingress:
    - fromEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: monitoring
            app.kubernetes.io/name: prometheus
            prometheus: kube-prometheus-stack-prometheus
      toPorts:
        - ports:
            - port: "9093"
              protocol: TCP
            - port: "8080"
              protocol: TCP
  egress:
    - toEndpoints:
        - matchLabels:
            k8s:io.kubernetes.pod.namespace: kube-system
            k8s-app: kube-dns
      toPorts:
        - ports:
            - port: "53"
              protocol: UDP
            - port: "53"
              protocol: TCP
          rules:
            dns:
              - matchPattern: "*"
    - toFQDNs:
        - matchName: discord.com
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
\`\`\`

- [ ] **Step 7: Enable ServiceMonitors and dependency ordering**

Add to cert-manager values:

\`\`\`yaml
prometheus:
  enabled: true
  servicemonitor:
    enabled: true
    labels:
      release: kube-prometheus-stack
\`\`\`

Add the four new observability resources to its Kustomization. Add \`security-external-secrets\` to \`infra-observability.spec.dependsOn\` while retaining \`infra-istio-ambient\`.

- [ ] **Step 8: Run GREEN**

\`\`\`powershell
pwsh -NoProfile -File scripts/test-runtime-alerting.ps1
kubectl kustomize gitops/infrastructure/observability
kubectl kustomize gitops/infrastructure/cert-manager
\`\`\`

Expected: exit 0 and \`Runtime alerting contracts passed\`.

- [ ] **Step 9: Commit**

\`\`\`powershell
git add scripts/test-runtime-alerting.ps1 gitops/infrastructure/observability gitops/infrastructure/cert-manager/release.yaml gitops/clusters/production/infrastructure.yaml
git commit -m "feat(observability): route actionable alerts to Discord"
\`\`\`

---

### Task 4: CI gate, operator runbook, and honest backlog status

**Files:**
- Create: \`scripts/test-alerting-docs-and-ci.ps1\`
- Create: \`scripts/test-gitops-alerting.ps1\`
- Modify: \`.github/workflows/security-scan-iac.yaml\`
- Create: \`docs/runbooks/discord-alerting.md\`
- Modify: \`REQUIRED_SECRETS.md\`
- Modify: \`BACKLOG_BY_DOMAIN.md\`
- Modify: \`docs/superpowers/specs/2026-07-10-discord-alerting-design.md\`

**Interfaces:**
- Consumes: the three implementation contract scripts.
- Produces: a single CI entry point, exact Vault provisioning instructions, GitHub→Discord manual steps, and accurate deferred-work notes.

- [ ] **Step 1: Write the docs and CI contract test**

\`\`\`powershell
. (Join-Path $PSScriptRoot "gitops-test-helpers.ps1")

$workflow = Read-RepoFile ".github/workflows/security-scan-iac.yaml"
$requiredSecrets = Read-RepoFile "REQUIRED_SECRETS.md"
$backlog = Read-RepoFile "BACKLOG_BY_DOMAIN.md"
$runbook = Read-RepoFile "docs/runbooks/discord-alerting.md"

Assert-Matches $workflow "pwsh -NoProfile -File scripts/test-gitops-alerting\.ps1" "IaC CI must execute alert contracts"
Assert-Matches $workflow 'scripts/\*\*\.ps1' "contract changes must trigger IaC CI"
Assert-Matches $requiredSecrets "DISCORD_ALERTS_WEBHOOK_URL" "operators need the exact Vault key"
Assert-Matches $runbook "DISCORD_ALERTS_WEBHOOK_URL" "runbook must document the Vault key"
Assert-Matches $runbook "/github" "runbook must document Discord's GitHub endpoint"
Assert-Matches $backlog "SMTP.*후속|이메일.*후속" "email routing must remain deferred"

$trackedText = $workflow + $requiredSecrets + $backlog + $runbook
Assert-NoMatches $trackedText "https://discord\.com/api/webhooks/[0-9]" "No real Webhook URL may be tracked"

Write-Host "Alerting documentation and CI contracts passed"
\`\`\`

- [ ] **Step 2: Run RED**

Run \`pwsh -NoProfile -File scripts/test-alerting-docs-and-ci.ps1\`.

Expected: FAIL with \`[MISSING] docs/runbooks/discord-alerting.md\`.

- [ ] **Step 3: Add the aggregate test**

\`\`\`powershell
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$tests = @(
    "test-flux-notifications.ps1",
    "test-flagger-alerting.ps1",
    "test-runtime-alerting.ps1",
    "test-alerting-docs-and-ci.ps1"
)

foreach ($test in $tests) {
    & (Join-Path $PSScriptRoot $test)
}

Write-Host "All GitOps alerting contracts passed"
\`\`\`

- [ ] **Step 4: Wire the contract into IaC CI**

Add \`"scripts/**.ps1"\` to both push and pull-request path filters. Add before Trivy:

\`\`\`yaml
- name: Validate GitOps alerting contracts
  shell: pwsh
  run: pwsh -NoProfile -File scripts/test-gitops-alerting.ps1
\`\`\`

- [ ] **Step 5: Write the runbook and secret checklist**

The runbook must state all of the following, with no secret value:

1. Create one Discord incoming Webhook for the operations channel.
2. Store its base URL in OCI Vault as \`DISCORD_ALERTS_WEBHOOK_URL\`.
3. Do not append \`/github\` to the Vault value; cluster integrations use the base URL.
4. In GitHub Settings → Webhooks, use the base URL plus \`/github\`, content type \`application/json\`, and select actionable check, issue, PR, Dependabot, and release events.
5. Verify ExternalSecret readiness for \`flux-system/flux-discord-webhook\`, \`backend/flagger-discord-webhook\`, and \`monitoring/alertmanager-discord-webhook\`.
6. Inspect Provider, AlertProvider, Alertmanager, and PrometheusRule readiness without deliberately breaking production.
7. Rotate by updating the one Vault value, validating all three local Secrets after ESO refresh, then removing the old Discord webhook.

Add \`DISCORD_ALERTS_WEBHOOK_URL\` to the OCI Vault section in \`REQUIRED_SECRETS.md\`, describing it as an incoming Webhook URL rather than a Bot token.

- [ ] **Step 6: Update backlog status honestly**

Annotate Flux, Flagger, and Alertmanager as “GitOps implementation complete; Vault provisioning and runtime delivery verification pending.” Keep GitHub→Discord unchecked until its external setting is completed. Keep SMTP/email and operational drills explicitly deferred.

- [ ] **Step 7: Run GREEN**

\`\`\`powershell
pwsh -NoProfile -File scripts/test-gitops-alerting.ps1
kubectl kustomize gitops/clusters/production/flux-system
kubectl kustomize gitops/infrastructure/notifications
kubectl kustomize gitops/infrastructure/flagger
kubectl kustomize gitops/infrastructure/observability
kubectl kustomize gitops/infrastructure/cert-manager
kubectl kustomize gitops/apps/backend-springboot
\`\`\`

Expected: every command exits 0.

- [ ] **Step 8: Commit**

\`\`\`powershell
git add scripts/test-alerting-docs-and-ci.ps1 scripts/test-gitops-alerting.ps1 .github/workflows/security-scan-iac.yaml docs/runbooks/discord-alerting.md REQUIRED_SECRETS.md BACKLOG_BY_DOMAIN.md docs/superpowers/specs/2026-07-10-discord-alerting-design.md
git commit -m "docs(alerting): add provisioning runbook and CI gate"
\`\`\`

---

### Task 5: Whole-branch verification and review

**Files:**
- Verify all files changed since \`origin/main\`; do not add new scope.

- [ ] **Step 1: Run contracts and offline renderers**

\`\`\`powershell
pwsh -NoProfile -File scripts/test-gitops-alerting.ps1
kubectl kustomize gitops/clusters/production/flux-system
kubectl kustomize gitops/infrastructure/notifications
kubectl kustomize gitops/infrastructure/flagger
kubectl kustomize gitops/infrastructure/observability
kubectl kustomize gitops/infrastructure/cert-manager
kubectl kustomize gitops/apps/backend-springboot
\`\`\`

- [ ] **Step 2: Run application regressions sequentially**

Run \`npm test\` from \`apps/frontend/react-app\` and \`.\mvnw.cmd test\` from \`apps/backend/springboot-app\`. Expected baseline is 28 frontend tests and 57 backend tests, zero failures.

- [ ] **Step 3: Check diff and secrets**

\`\`\`powershell
git diff --check origin/main...HEAD
rg -n "https://discord\.com/api/webhooks/[0-9]|DISCORD_[A-Z_]*=.+" gitops docs scripts REQUIRED_SECRETS.md BACKLOG_BY_DOMAIN.md
\`\`\`

Expected: diff check exits 0; ripgrep finds no actual Webhook URL or assignment.

- [ ] **Step 4: Render official chart compatibility slices**

\`\`\`powershell
helm template flagger flagger/flagger --version 1.43.0 --namespace flagger-system --set serviceMonitor.enabled=true --set serviceMonitor.labels.release=kube-prometheus-stack
helm template kube-prometheus-stack prometheus-community/kube-prometheus-stack --version 75.18.1 --namespace monitoring --set alertmanager.enabled=true --set alertmanager.alertmanagerSpec.retention=24h --set alertmanager.alertmanagerSpec.secrets[0]=alertmanager-discord-webhook
helm template cert-manager jetstack/cert-manager --version v1.17.4 --namespace cert-manager --set prometheus.enabled=true --set prometheus.servicemonitor.enabled=true --set prometheus.servicemonitor.labels.release=kube-prometheus-stack
\`\`\`

- [ ] **Step 5: Whole-branch review**

Review against the approved design, all four AGENTS principles, RED/GREEN evidence, secret safety, egress completeness, one-Alertmanager resource budget, and honest deferred backlog status. Fix every Critical or Important finding and rerun affected verification before reporting completion.
