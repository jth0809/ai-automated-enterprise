# Alert Noise and Controller Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Do not dispatch subagents.

**Goal:** Recover cert-manager and Flux notification-controller API access while reducing Discord noise without hiding real failures.

**Architecture:** Two narrowly scoped Cilium policies retain their `kube-apiserver` identity restriction and add the observed OKE backend port 6443 alongside Service port 443. The single global `AlertmanagerConfig` keeps Discord as the default, discards only informational and two structural overcommit alerts, groups warnings by namespace, and preserves faster critical delivery.

**Tech Stack:** Kubernetes 1.36 on OKE, Cilium 1.17 policy, FluxCD, cert-manager 1.17.4, Prometheus Operator `AlertmanagerConfig`, kube-prometheus-stack 75.18.1, Alertmanager 0.28.1, PowerShell contract tests, Kustomize

## Global Constraints

- GitOps is the only production mutation path; never run `kubectl apply`, patch, delete, rollout restart, or manually reconcile production.
- Preserve `toEntities: kube-apiserver`; add only TCP 6443 and never broaden to `world`, `cluster`, `host`, a CIDR, or all ports.
- Keep Discord credentials solely in OCI Vault key `DISCORD_ALERTS_WEBHOOK_URL` and Secret `monitoring/alertmanager-discord-webhook[address]`.
- Keep unknown future warnings and critical alerts routed to Discord by default.
- Keep `sendResolved: true`; a transient RESOLVED is not recovery unless the workload remains Ready for at least ten minutes with no restart increase and no pending/firing alert.
- Keep exactly one Alertmanager replica and the current resource budget.
- Do not add workloads, storage, Loki, email routing, or application notification code.
- Do not dispatch subagents.

---

## File map

- `scripts/test-runtime-alerting.ps1`: rendered contracts for cert-manager Cilium API egress and Alertmanager routing.
- `scripts/test-flux-notifications.ps1`: rendered contracts for notification-controller's isolated egress.
- `gitops/infrastructure/cert-manager/network-policy.yaml`: cert-manager controller DNS, Kubernetes API, ACME, and HTTP-01 egress allowlist.
- `gitops/clusters/production/flux-system/notification-egress.yaml`: notification-controller DNS, Kubernetes API, and Discord egress allowlist.
- `gitops/infrastructure/observability/alertmanager-config.yaml`: sole global Alertmanager route, inhibition, and receiver definition.
- `scripts/test-alerting-docs-and-ci.ps1`: README, backlog, and runbook accuracy contracts.
- `docs/runbooks/discord-alerting.md`: operator interpretation, routing policy, and recovery checks.
- `README.md`: high-level live-delivery status and routing summary.
- `BACKLOG_BY_DOMAIN.md`: separates verified Alertmanager delivery from still-pending direct Flux/Flagger delivery.

### Task 1: Restore isolated controller API access

**Files:**
- Modify: `scripts/test-runtime-alerting.ps1`
- Modify: `scripts/test-flux-notifications.ps1`
- Modify: `gitops/infrastructure/cert-manager/network-policy.yaml`
- Modify: `gitops/clusters/production/flux-system/notification-egress.yaml`

**Interfaces:**
- Consumes: Cilium's observed Service translation `10.96.0.1:443 -> 10.0.0.73:6443`.
- Produces: TCP 443 and 6443 access only to identity `kube-apiserver` for the two selected controllers.

- [ ] **Step 1: Add the failing cert-manager API-port contract**

After `$certManagerPolicy` is created in `scripts/test-runtime-alerting.ps1`, extract the API rule:

```powershell
$certManagerApiEgress = [regex]::Match(
    $certManagerPolicy,
    '(?ms)^  - toEntities:\n.*?(?=^  - to(?:Endpoints|Entities|FQDNs):|\z)'
).Value
```

Add these assertions beside the existing cert-manager destination checks:

```powershell
if ($certManagerApiEgress.Length -eq 0) {
    throw "[ASSERT] cert-manager needs a Kubernetes API egress block"
}
Assert-MatchCount $certManagerApiEgress '(?m)^      - port: "443"$' 1 "cert-manager API egress must preserve the Service port"
Assert-MatchCount $certManagerApiEgress '(?m)^      - port: "6443"$' 1 "cert-manager API egress must allow the observed OKE backend port"
Assert-MatchCount $certManagerApiEgress '(?m)^        protocol: TCP$' 2 "cert-manager API egress must contain only two TCP ports"
Assert-MatchCount $certManagerPolicy '(?m)^      - port: "6443"$' 1 "cert-manager must expose the OKE API backend port only once"
```

Change the final cert-manager total-port assertion from 7 to 8:

```powershell
Assert-MatchCount $certManagerPolicy '(?m)^\s+- port: "[^\"]+"$' 8 "cert-manager policy must contain no extra ports"
```

- [ ] **Step 2: Add the failing notification-controller API-port contract**

Normalize and extract the API block in `scripts/test-flux-notifications.ps1`:

```powershell
$egressPolicy = (Get-RenderedResource $fluxBootstrap "CiliumNetworkPolicy") -replace "`r`n", "`n"
$apiEgressPolicy = [regex]::Match(
    $egressPolicy,
    '(?ms)^  - toEntities:\n.*?(?=^  - to(?:Endpoints|Entities|FQDNs):|\z)'
).Value
```

Add these assertions after the destination-family checks:

```powershell
if ($apiEgressPolicy.Length -eq 0) {
    throw "[ASSERT] notification-controller needs a Kubernetes API egress block"
}
Assert-MatchCount $apiEgressPolicy '(?m)^      - port: "443"$' 1 "notification API egress must preserve the Service port"
Assert-MatchCount $apiEgressPolicy '(?m)^      - port: "6443"$' 1 "notification API egress must allow the observed OKE backend port"
Assert-MatchCount $apiEgressPolicy '(?m)^        protocol: TCP$' 2 "notification API egress must contain only two TCP ports"
```

Replace the aggregate port assertions with:

```powershell
Assert-MatchCount $egressPolicy '(?m)^      - port: "443"$' 2 "API Service and Discord egress must expose TCP port 443"
Assert-MatchCount $egressPolicy '(?m)^      - port: "6443"$' 1 "API backend egress must expose TCP port 6443 once"
Assert-MatchCount $egressPolicy '(?m)^\s+- port: "[^"]+"$' 5 "egress must contain exactly five intended port entries"
Assert-MatchCount $egressPolicy "(?m)^\s+protocol: TCP$" 4 "egress must contain exactly four TCP ports"
```

- [ ] **Step 3: Run both focused contracts and verify RED**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-flux-notifications.ps1
```

Expected: both fail on the missing TCP 6443 assertion. They must not fail from a regex or rendering error.

- [ ] **Step 4: Add the minimal cert-manager policy port**

Change only the existing `toEntities: kube-apiserver` port list in `gitops/infrastructure/cert-manager/network-policy.yaml`:

```yaml
    - toEntities:
        - kube-apiserver
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
            - port: "6443"
              protocol: TCP
```

- [ ] **Step 5: Add the minimal notification-controller policy port**

Change only the existing `toEntities: kube-apiserver` port list in `gitops/clusters/production/flux-system/notification-egress.yaml`:

```yaml
    - toEntities:
        - kube-apiserver
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
            - port: "6443"
              protocol: TCP
```

- [ ] **Step 6: Run both focused contracts and verify GREEN**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-flux-notifications.ps1
```

Expected:

```text
Runtime alerting contracts passed
Flux notification contracts passed
```

- [ ] **Step 7: Commit the API egress repair**

```powershell
git add -- scripts/test-runtime-alerting.ps1 scripts/test-flux-notifications.ps1 gitops/infrastructure/cert-manager/network-policy.yaml gitops/clusters/production/flux-system/notification-egress.yaml
git commit -m "fix(gitops): allow OKE API backend port"
```

### Task 2: Make Discord routing incident-oriented

**Files:**
- Modify: `scripts/test-runtime-alerting.ps1`
- Modify: `gitops/infrastructure/observability/alertmanager-config.yaml`

**Interfaces:**
- Consumes: the existing Vault-backed `discord` and `null` receivers.
- Produces: namespace-grouped warnings, faster critical overrides, and narrow null routes.

- [ ] **Step 1: Replace the old routing assertions with failing route contracts**

After `$alertmanagerConfig` is created, add:

```powershell
$alertmanagerRoute = [regex]::Match($alertmanagerConfig, '(?ms)^  route:\n.*\z').Value
$alertmanagerRootRoute = [regex]::Match($alertmanagerRoute, '(?ms)^  route:\n.*?(?=^    routes:)').Value
$alertmanagerChildRoutes = [regex]::Match($alertmanagerRoute, '(?ms)^    routes:\n.*\z').Value
```

Replace the existing `groupBy`, `groupWait`, and `repeatInterval` assertions with:

```powershell
Assert-MatchCount $alertmanagerRootRoute '(?m)^    - namespace$' 1 "root alerts must group by namespace"
Assert-NoMatches $alertmanagerRootRoute '(?m)^    - alertname$' "root grouping must coalesce related alert names"
Assert-Matches $alertmanagerRootRoute '(?m)^    groupWait: 2m$' "warnings must wait two minutes for related symptoms"
Assert-Matches $alertmanagerRootRoute '(?m)^    groupInterval: 5m$' "notification group interval must remain five minutes"
Assert-Matches $alertmanagerRootRoute '(?m)^    repeatInterval: 12h$' "warnings must repeat no more than twice daily"
Assert-MatchCount $alertmanagerChildRoutes '(?m)^      receiver: "null"$' 3 "only three child routes may discard alerts"
Assert-Matches $alertmanagerChildRoutes '(?ms)name: alertname\n\s+value: Watchdog\n\s+receiver: "null"' "Watchdog must route to null"
Assert-Matches $alertmanagerChildRoutes '(?ms)name: severity\n\s+value: info\n\s+receiver: "null"' "informational alerts must route directly to null"
Assert-Matches $alertmanagerChildRoutes '(?ms)matchType: =~\n\s+name: alertname\n\s+value: KubeCPUOvercommit\|KubeMemoryOvercommit\n\s+receiver: "null"' "only the structural overcommit alerts must route to null"
Assert-Matches $alertmanagerChildRoutes '(?ms)groupWait: 30s.*?name: severity\n\s+value: critical\n\s+receiver: discord\n\s+repeatInterval: 4h' "critical alerts must retain fast delivery and repetition"
Assert-MatchCount $alertmanagerRoute '(?m)^\s+groupWait: 30s$' 1 "only critical alerts may use the short wait"
Assert-MatchCount $alertmanagerRoute '(?m)^\s+repeatInterval: 4h$' 1 "only critical alerts may use the short repeat"
```

Keep the receiver, Secret selector, `sendResolved`, and inhibition assertions unchanged.

- [ ] **Step 2: Run the runtime contract and verify RED**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
```

Expected: failure that the root route still groups by alert name or lacks `groupWait: 2m`. It must not fail while extracting the rendered route.

- [ ] **Step 3: Implement the minimal global route**

Replace only `spec.route` in `gitops/infrastructure/observability/alertmanager-config.yaml`:

```yaml
  route:
    receiver: discord
    groupBy:
      - namespace
    groupWait: 2m
    groupInterval: 5m
    repeatInterval: 12h
    routes:
      - receiver: "null"
        matchers:
          - name: alertname
            matchType: "="
            value: Watchdog
      - receiver: "null"
        matchers:
          - name: severity
            matchType: "="
            value: info
      - receiver: "null"
        matchers:
          - name: alertname
            matchType: "=~"
            value: KubeCPUOvercommit|KubeMemoryOvercommit
      - receiver: discord
        groupWait: 30s
        repeatInterval: 4h
        matchers:
          - name: severity
            matchType: "="
            value: critical
```

Do not modify `inhibitRules` or `receivers`.

- [ ] **Step 4: Run the runtime contract and verify GREEN**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
```

Expected: `Runtime alerting contracts passed`.

- [ ] **Step 5: Commit the routing policy**

```powershell
git add -- scripts/test-runtime-alerting.ps1 gitops/infrastructure/observability/alertmanager-config.yaml
git commit -m "fix(alerting): reduce Discord warning noise"
```

### Task 3: Record live delivery and flapping semantics

**Files:**
- Modify: `scripts/test-alerting-docs-and-ci.ps1`
- Modify: `docs/runbooks/discord-alerting.md`
- Modify: `README.md`
- Modify: `BACKLOG_BY_DOMAIN.md`

**Interfaces:**
- Consumes: Tasks 1 and 2 behavior plus the observed eight successful Discord sends.
- Produces: truthful runtime status that distinguishes Alertmanager delivery from direct Flux/Flagger delivery.

- [ ] **Step 1: Add failing documentation contracts**

Replace the README and backlog runtime-pending assertions in `scripts/test-alerting-docs-and-ci.ps1` with:

```powershell
Assert-Matches $readme "(?is)Alertmanager.*runtime delivery verified.*2026-07-18" "README must record verified Alertmanager delivery"
Assert-Matches $readme "(?is)Flux.*Flagger.*runtime delivery verification.*pending" "README must keep direct controller delivery pending"
Assert-Matches $backlog "(?is)Alertmanager.*runtime delivery verified.*2026-07-18" "backlog must close Alertmanager runtime delivery"
Assert-Matches $backlog "(?is)Flux.*Flagger.*runtime delivery verification pending" "backlog must retain the direct-provider gate"
```

Add runbook contracts:

```powershell
Assert-Matches $runbook "(?is)API backend.*6443" "runbook must explain the OKE API backend port"
Assert-Matches $runbook "(?is)severity=info.*null" "runbook must document informational suppression"
Assert-Matches $runbook "(?is)KubeCPUOvercommit.*KubeMemoryOvercommit.*null" "runbook must document structural overcommit suppression"
Assert-Matches $runbook "(?is)groupWait.*2m.*repeatInterval.*12h" "runbook must document warning batching and repetition"
Assert-Matches $runbook "(?is)10분.*restart.*pending.*firing" "runbook must reject transient resolved notifications as recovery"
```

Keep the historical 2026-07-10 design assertion unchanged because it describes the gate at design time.

- [ ] **Step 2: Run the docs contract and verify RED**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-alerting-docs-and-ci.ps1
```

Expected: failure that verified Alertmanager delivery or the 6443 recovery note is missing.

- [ ] **Step 3: Update the operator runbook**

Add a section that states:

```markdown
## Discord 라우팅 및 복구 판정

- warning은 `namespace`별로 `groupWait: 2m`, `repeatInterval: 12h`를 적용한다.
- critical은 `groupWait: 30s`, `repeatInterval: 4h`를 유지한다.
- `severity=info`와 `KubeCPUOvercommit|KubeMemoryOvercommit`은 `null`로 보내되 Prometheus/Grafana에는 보존한다.
- OKE의 Kubernetes Service 443은 현재 API backend TCP 6443으로 변환되므로 두 격리 컨트롤러 CNP가 `kube-apiserver`의 443과 6443만 허용해야 한다.
- RESOLVED 한 건만으로 복구를 선언하지 않는다. 최소 10분 동안 Ready이고 restart 증가가 없으며 Prometheus에 해당 alert의 pending/firing 상태가 모두 없어야 한다.
```

Document the read-only commands for Deployment/Pod readiness and Prometheus alert inspection without printing Secret values.

- [ ] **Step 4: Update README and backlog status**

Record the stable English status token `Alertmanager runtime delivery verified 2026-07-18` next to the existing Korean explanation. State separately that direct Flux and Flagger runtime delivery verification remains pending until notification-controller is healthy and a natural event is observed.

Make the same distinction in `BACKLOG_BY_DOMAIN.md`; do not mark GitHub external Webhook settings, Flux direct delivery, Flagger direct delivery, email, or Dependabot direct events complete.

- [ ] **Step 5: Run the documentation contract and verify GREEN**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-alerting-docs-and-ci.ps1
```

Expected: `Alerting documentation and CI contracts passed`.

- [ ] **Step 6: Commit the documentation status**

```powershell
git add -- scripts/test-alerting-docs-and-ci.ps1 docs/runbooks/discord-alerting.md README.md BACKLOG_BY_DOMAIN.md
git commit -m "docs(alerting): record delivery and recovery policy"
```

### Task 4: Verify, push, and observe the GitOps rollout

**Files:**
- Verify only before push; no additional planned production changes.

**Interfaces:**
- Consumes: Tasks 1 through 3.
- Produces: review evidence and a read-only post-merge health report.

- [ ] **Step 1: Run the complete alerting contract suite**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
```

Expected: all component messages followed by `All GitOps alerting contracts passed`.

- [ ] **Step 2: Render every changed GitOps boundary**

```powershell
kubectl kustomize gitops/infrastructure/cert-manager
kubectl kustomize gitops/clusters/production/flux-system
kubectl kustomize gitops/infrastructure/observability
```

Expected: exit code 0 for all three; the first two contain `kube-apiserver`, ports 443 and 6443, and no broad destination; observability contains one `AlertmanagerConfig` and no real Discord URL.

- [ ] **Step 3: Run pinned chart schema rendering**

```powershell
helm template kube-prometheus-stack prometheus-community/kube-prometheus-stack --version 75.18.1 --namespace monitoring --set alertmanager.enabled=true
helm template cert-manager jetstack/cert-manager --version v1.17.4 --namespace cert-manager --set prometheus.enabled=true --set prometheus.servicemonitor.enabled=true --set prometheus.servicemonitor.labels.release=kube-prometheus-stack
```

Expected: exit code 0 with no schema or template errors.

- [ ] **Step 4: Check Secret safety, whitespace, and branch scope**

```powershell
git diff --check origin/main...HEAD
git status --short
git diff --stat origin/main...HEAD
```

Expected: no whitespace errors, a clean worktree, and changes limited to the approved design, plan, contracts, policies, Alertmanager route, README, backlog, and runbook.

- [ ] **Step 5: Push the review branch**

```powershell
git push origin codex/fix-alertmanager-discord-config
```

Expected: the remote branch advances without rewriting history. Create or update the PR; do not push directly to `main`.

- [ ] **Step 6: Verify production only after normal Flux reconciliation**

Use read-only `kubectl get`, `kubectl logs`, Cilium endpoint policy inspection, Prometheus HTTP API, and Alertmanager HTTP API. Do not force reconciliation or restart Pods.

Recovery requires all of the following for at least ten minutes:

- cert-manager and notification-controller Deployments are `1/1` Available;
- both Pods are Ready and restart counts do not increase;
- cert-manager no longer logs API TLS timeout/reset;
- the three cert-manager and two Flux symptom alerts have neither pending nor firing series;
- Alertmanager routes info and overcommit groups to `null` while warnings and critical alerts retain Discord;
- the final real-incident RESOLVED notification is delivered once.

If the 6443 policy does not recover both workloads, stop and return to root-cause investigation. Do not add a third port or a broad destination as a follow-up guess.
