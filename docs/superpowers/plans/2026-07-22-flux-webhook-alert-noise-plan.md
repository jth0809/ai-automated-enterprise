# Flux Webhook Alert Noise Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Do not dispatch subagents.

**Goal:** Suppress only self-healing Kustomization admission-webhook EOF events while preserving immediate actionable Flux notifications and emitting one Discord warning for a Kustomization that remains NotReady for five minutes.

**Architecture:** Stage 1 extends the existing kube-state-metrics deployment with the official Flux `gotk_resource_info` metric, adds a five-minute Prometheus rule, and gives that rule a dedicated Alertmanager route. Stage 2 is a separate GitOps rollout after runtime proof: it partitions Kustomization and HelmRelease Flux Alerts and applies one exact, message-anchored EOF exclusion only to Kustomizations.

**Tech Stack:** Flux notification-controller v1beta3 Alerts, kube-prometheus-stack 75.18.1, kube-state-metrics CustomResourceStateMetrics, Prometheus Operator PrometheusRule and AlertmanagerConfig, PowerShell contract tests, Kustomize, Helm, Cilium, Istio Ambient Mesh

## Global Constraints

- GitOps is the only production mutation path; never use `kubectl apply`, imperative reconciliation, or manual production patching.
- Do not change admission webhook `failurePolicy`, validation webhooks, certificates, or controller replicas.
- Do not add a Flux controller PodMonitor or any new inter-service network path, NetworkPolicy, or AuthorizationPolicy.
- Grant kube-state-metrics only `list` and `watch` on `kustomizations.kustomize.toolkit.fluxcd.io`; retain all existing core collectors.
- The sustained alert is `FluxKustomizationNotReady`, severity `warning`, with `for: 5m`, scoped to non-suspended `flux-system` Kustomizations whose Ready condition is not `True`.
- Preserve immediate direct delivery for every non-matching Kustomization error and every HelmRelease error.
- Preserve root warning timing, critical timing, null routes, inhibition, `sendResolved: true`, resource limits, Vault references, Cilium policy, and Istio identity policy.
- Stage 2 must not be deployed until Stage 1 is merged, reconciled, and the replacement metric and rule pass the read-only runtime gate.
- Do not deliberately fail a production Kustomization to test this behavior.
- Do not delete existing files and do not dispatch subagents.

---

## File map

- `scripts/test-runtime-alerting.ps1`: rendered contracts for kube-state-metrics RBAC/configuration, the sustained rule, and its dedicated Alertmanager route.
- `gitops/infrastructure/observability/release.yaml`: existing kube-state-metrics Helm values; gains only Kustomization read access and one custom-resource metric definition.
- `gitops/infrastructure/observability/alert-rules.yaml`: owns `FluxKustomizationNotReady` and its exact PromQL persistence contract.
- `gitops/infrastructure/observability/alertmanager-config.yaml`: owns the dedicated warning route and leaves all existing routes unchanged.
- `scripts/test-flux-notifications.ps1`: rendered contracts and sample-message semantics for source partitioning and the narrow EOF exclusion.
- `gitops/infrastructure/notifications/alert.yaml`: owns the two non-overlapping direct Flux Alerts.
- `scripts/test-alerting-docs-and-ci.ps1`: operator-documentation contract for the new direct and sustained paths.
- `docs/runbooks/discord-alerting.md`: read-only verification and incident interpretation for the new behavior.

### Task 1: Export Kustomization Ready state and define the sustained alert

**Files:**
- Modify: `scripts/test-runtime-alerting.ps1:3-14,56-68`
- Modify: `gitops/infrastructure/observability/release.yaml:94-99`
- Modify: `gitops/infrastructure/observability/alert-rules.yaml:42-54`

**Interfaces:**
- Consumes: Flux `kustomize.toolkit.fluxcd.io/v1` Kustomization objects through the existing kube-state-metrics Kubernetes API client.
- Produces: `gotk_resource_info{customresource_kind="Kustomization",exported_namespace,name,ready,suspended}` and Prometheus alert `FluxKustomizationNotReady`.

- [ ] **Step 1: Add the failing Stage 1 metric and rule contracts**

In `scripts/test-runtime-alerting.ps1`, add these rendered-resource variables after `$alertmanagerConfig`:

```powershell
$kubePrometheusRelease = Get-RenderedResourceByName -Rendered $observability -Kind "HelmRelease" -Name "kube-prometheus-stack"
$platformAlertRules = Get-RenderedResourceByName -Rendered $observability -Kind "PrometheusRule" -Name "platform-actionable-alerts"
```

Add these assertions immediately after the existing Flagger rule assertions:

```powershell
Assert-Matches $kubePrometheusRelease '(?ms)kube-state-metrics:.*?rbac:.*?extraRules:.*?apiGroups:.*?kustomize\.toolkit\.fluxcd\.io.*?resources:.*?kustomizations.*?verbs:.*?list.*?watch' "kube-state-metrics must have least-privilege Kustomization read access"
Assert-NoMatches $kubePrometheusRelease 'custom-resource-state-only' "core kube-state-metrics collectors must remain enabled"
Assert-Matches $kubePrometheusRelease '(?ms)customResourceState:.*?enabled: true.*?kind: CustomResourceStateMetrics' "kube-state-metrics custom resource state support must be enabled"
Assert-Matches $kubePrometheusRelease '(?m)^\s+group: kustomize\.toolkit\.fluxcd\.io$' "the exact Flux Kustomization API group must be configured"
Assert-Matches $kubePrometheusRelease '(?m)^\s+version: v1$' "the stable Flux Kustomization API version must be configured"
Assert-Matches $kubePrometheusRelease '(?m)^\s+kind: Kustomization$' "only the Flux Kustomization kind must be configured"
Assert-Matches $kubePrometheusRelease '(?m)^\s+metricNamePrefix: gotk$' "the Flux metric prefix must be gotk"
Assert-Matches $kubePrometheusRelease '(?m)^\s+(?:- )?name: resource_info$' "the documented resource_info metric must be configured"
Assert-Matches $kubePrometheusRelease '(?m)^\s+type: Info$' "gotk_resource_info must be an Info metric"
Assert-Matches $kubePrometheusRelease '(?ms)exported_namespace:.*?metadata.*?namespace.*?ready:.*?status.*?conditions.*?\[type=Ready\].*?status.*?suspended:.*?spec.*?suspend' "gotk_resource_info must expose namespace, Ready, and suspend state"
Assert-MatchCount $kubePrometheusRelease '(?m)^\s+group: kustomize\.toolkit\.fluxcd\.io$' 1 "only one Flux custom-resource metric group may be configured"
Assert-Matches $platformAlertRules '(?m)^\s+- alert: FluxKustomizationNotReady$' "the sustained Flux alert must exist"
Assert-Matches $platformAlertRules 'gotk_resource_info\{' "the sustained Flux alert must use the kube-state-metrics resource metric"
Assert-Matches $platformAlertRules 'customresource_group="kustomize\.toolkit\.fluxcd\.io"' "the rule must select the exact Flux API group"
Assert-Matches $platformAlertRules 'customresource_kind="Kustomization"' "the rule must select only Kustomizations"
Assert-Matches $platformAlertRules 'exported_namespace="flux-system"' "the rule must retain the existing Flux Alert namespace boundary"
Assert-Matches $platformAlertRules 'ready!="True"' "False, Unknown, and initially absent Ready conditions must count as NotReady"
Assert-Matches $platformAlertRules 'suspended!="true"' "intentionally suspended Kustomizations must not alert"
Assert-Matches $platformAlertRules '(?ms)alert: FluxKustomizationNotReady.*?for: 5m.*?severity: warning' "NotReady must persist for five minutes before warning"
Assert-MatchCount $platformAlertRules '(?m)^\s+- alert: FluxKustomizationNotReady$' 1 "the sustained Flux rule must be unique"
```

- [ ] **Step 2: Run the focused contract and verify RED**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
```

Expected: failure containing `kube-state-metrics must have least-privilege Kustomization read access` because Stage 1 values are not present.

- [ ] **Step 3: Add least-privilege CustomResourceStateMetrics values**

Replace the current `kube-state-metrics` block in `gitops/infrastructure/observability/release.yaml` with the following, retaining the existing resources verbatim:

```yaml
    kube-state-metrics:
      rbac:
        extraRules:
          - apiGroups:
              - kustomize.toolkit.fluxcd.io
            resources:
              - kustomizations
            verbs:
              - list
              - watch
      customResourceState:
        enabled: true
        config:
          kind: CustomResourceStateMetrics
          spec:
            resources:
              - groupVersionKind:
                  group: kustomize.toolkit.fluxcd.io
                  version: v1
                  kind: Kustomization
                metricNamePrefix: gotk
                metrics:
                  - name: resource_info
                    help: "The current state of a Flux Kustomization resource."
                    each:
                      type: Info
                      info:
                        labelsFromPath:
                          name: [metadata, name]
                    labelsFromPath:
                      exported_namespace: [metadata, namespace]
                      ready: [status, conditions, "[type=Ready]", status]
                      suspended: [spec, suspend]
      resources:
        requests:
          cpu: 10m
          memory: 64Mi
        limits:
          memory: 128Mi
```

Do not add `collectors`, `extraArgs`, `--custom-resource-state-only`, or another Flux resource kind.

- [ ] **Step 4: Add the five-minute Prometheus rule**

Append this group to `gitops/infrastructure/observability/alert-rules.yaml` before `platform-flagger`:

```yaml
    - name: platform-flux
      rules:
        - alert: FluxKustomizationNotReady
          expr: |
            max by (exported_namespace, name) (
              gotk_resource_info{
                customresource_group="kustomize.toolkit.fluxcd.io",
                customresource_kind="Kustomization",
                exported_namespace="flux-system",
                ready!="True",
                suspended!="true"
              }
            ) == 1
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Flux Kustomization {{ $labels.exported_namespace }}/{{ $labels.name }} remains NotReady"
            description: "The Ready condition has remained non-True for at least five minutes."
            runbook: "Inspect the Kustomization condition and controller logs; repair only through GitOps."
```

- [ ] **Step 5: Run focused and full alerting contracts**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
```

Expected: `Runtime alerting contracts passed` and `All GitOps alerting contracts passed`.

- [ ] **Step 6: Commit the state signal and rule**

```powershell
git add -- scripts/test-runtime-alerting.ps1 gitops/infrastructure/observability/release.yaml gitops/infrastructure/observability/alert-rules.yaml
git commit -m "feat(alerting): detect sustained Flux reconciliation failures"
```

### Task 2: Route the sustained warning without changing global timing

**Files:**
- Modify: `scripts/test-runtime-alerting.ps1:42-57`
- Modify: `gitops/infrastructure/observability/alertmanager-config.yaml:20-34`

**Interfaces:**
- Consumes: `FluxKustomizationNotReady` from Task 1.
- Produces: a Discord child route grouped by `alertname` and `exported_namespace`, with `groupWait: 30s` and `repeatInterval: 12h`.

- [ ] **Step 1: Add the failing dedicated-route contract**

Add this assertion before the existing critical-route assertion:

```powershell
Assert-Matches $alertmanagerChildRoutes '(?ms)groupBy:\n\s+- alertname\n\s+- exported_namespace\n\s+groupWait: 30s\n\s+matchers:.*?name: alertname\n\s+value: FluxKustomizationNotReady\n\s+receiver: discord\n\s+repeatInterval: 12h' "sustained Flux warnings must use the dedicated Discord route"
```

Replace the old short-wait count assertion with:

```powershell
Assert-MatchCount $alertmanagerRoute '(?m)^[ \t]+(?:- )?groupWait: 30s\r?$' 2 "only sustained Flux warnings and critical alerts may use the short wait"
```

Keep the existing `repeatInterval: 4h` count at exactly one.

- [ ] **Step 2: Run the focused contract and verify RED**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
```

Expected: failure containing `sustained Flux warnings must use the dedicated Discord route`.

- [ ] **Step 3: Add the child route before the critical route**

Insert this route after the three null routes and before the existing critical route in `gitops/infrastructure/observability/alertmanager-config.yaml`:

```yaml
      - receiver: discord
        groupBy:
          - alertname
          - exported_namespace
        groupWait: 30s
        repeatInterval: 12h
        matchers:
          - name: alertname
            matchType: "="
            value: FluxKustomizationNotReady
```

Do not change the root route or critical child route.

- [ ] **Step 4: Run focused and full alerting contracts**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
```

Expected: both commands pass.

- [ ] **Step 5: Commit the dedicated route**

```powershell
git add -- scripts/test-runtime-alerting.ps1 gitops/infrastructure/observability/alertmanager-config.yaml
git commit -m "feat(alerting): route sustained Flux warnings"
```

### Task 3: Verify and deploy Stage 1, then enforce the runtime gate

**Files:**
- Verify only; no planned production-file changes.

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: read-only evidence that permits or blocks Stage 2.

- [ ] **Step 1: Run complete local verification**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
kubectl kustomize gitops/infrastructure/observability
git diff --check origin/main...HEAD
git status --short
```

Expected: all alerting contracts pass, Kustomize exits 0, no whitespace errors, and no uncommitted files.

- [ ] **Step 2: Render the exact Helm values with the pinned chart**

Run from PowerShell:

```powershell
$release = Get-Content -Encoding UTF8 gitops/infrastructure/observability/release.yaml
$valuesLine = [Array]::IndexOf($release, "  values:")
if ($valuesLine -lt 0) { throw "HelmRelease values block not found" }
$values = $release[($valuesLine + 1)..($release.Count - 1)] | ForEach-Object { $_ -replace '^    ', '' }
$rendered = ($values -join "`n") | helm template kube-prometheus-stack prometheus-community/kube-prometheus-stack --version 75.18.1 --namespace monitoring --values -
if ($LASTEXITCODE -ne 0) { throw "Pinned kube-prometheus-stack render failed" }
if (($rendered -join "`n") -notmatch 'kind: CustomResourceStateMetrics') { throw "CustomResourceStateMetrics config was not rendered" }
```

Expected: Helm exits 0 and the generated kube-state-metrics ConfigMap contains `kind: CustomResourceStateMetrics`.

- [ ] **Step 3: Push Stage 1 and create the first PR**

Push `codex/tune-flux-webhook-alerts` and create a PR containing the design, plan, Stage 1 metric, sustained rule, and Alertmanager route. The PR description must state that the direct EOF exclusion is intentionally absent pending runtime proof.

- [ ] **Step 4: After merge, verify Stage 1 read-only**

Run only read operations after normal Flux reconciliation:

```powershell
kubectl -n flux-system get kustomization infra-observability
kubectl -n monitoring get deployment kube-prometheus-stack-kube-state-metrics
kubectl get clusterrole kube-prometheus-stack-kube-state-metrics -o yaml
kubectl -n monitoring get prometheusrule platform-actionable-alerts -o yaml
kubectl -n monitoring get alertmanagerconfig platform-alertmanager -o yaml
```

Then, in a separate terminal, expose the existing kube-state-metrics service read-only:

```powershell
kubectl -n monitoring port-forward service/kube-prometheus-stack-kube-state-metrics 18080:8080
```

Query it without writing cluster state:

```powershell
$metrics = (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18080/metrics).Content
$series = $metrics -split "`n" | Where-Object { $_ -match '^gotk_resource_info\{' -and $_ -match 'customresource_kind="Kustomization"' -and $_ -match 'exported_namespace="flux-system"' }
if (-not $series) { throw "gotk_resource_info has no flux-system Kustomization series" }
$series
```

Pass criteria:

- `infra-observability` is Ready;
- kube-state-metrics has an available replica with no new restart loop;
- ClusterRole contains Kustomization `list` and `watch`, with no write verbs;
- `gotk_resource_info` contains every `flux-system` Kustomization and its `ready` label;
- `FluxKustomizationNotReady` and its dedicated Alertmanager route are present;
- existing Prometheus and Alertmanager health remain normal.

**Hard gate:** stop here if any criterion fails. Do not create or deploy the EOF exclusion until all criteria pass.

### Task 4: Partition direct Flux sources and exclude only exact Kustomization EOF events

**Files:**
- Modify: `scripts/test-flux-notifications.ps1:3-24`
- Modify: `gitops/infrastructure/notifications/alert.yaml:1-18`

**Interfaces:**
- Consumes: the proven Stage 1 replacement signal.
- Produces: `Alert/platform-errors` for Kustomizations with one narrow exclusion and `Alert/platform-helm-errors` for unfiltered HelmRelease errors.

- [ ] **Step 1: Start Stage 2 from merged main**

After the Task 3 gate passes, create `codex/tune-flux-webhook-alerts-stage2` from the updated `origin/main`. Do not layer Stage 2 onto an unmerged Stage 1 branch.

- [ ] **Step 2: Add failing source-partition and regex-semantics contracts**

Add these variables after `$notifications` is built:

```powershell
$kustomizationAlert = Get-RenderedResourceByName -Rendered $notifications -Kind "Alert" -Name "platform-errors"
$helmReleaseAlert = Get-RenderedResourceByName -Rendered $notifications -Kind "Alert" -Name "platform-helm-errors"
$admissionEofExclusion = 'failed calling webhook "[^"]+": failed to call webhook: Post "https://[^"]+": EOF$'
```

Replace the current single-Alert and combined-source assertions with:

```powershell
Assert-MatchCount $notifications "(?m)^kind: Alert\r?$" 2 "notifications must define exactly two direct Flux Alerts"
foreach ($directAlert in @($kustomizationAlert, $helmReleaseAlert)) {
    Assert-Matches $directAlert "(?m)^\s+name: discord\r?$" "each Flux Alert must use the Discord provider"
    Assert-Matches $directAlert "(?m)^\s+eventSeverity: error\r?$" "each Flux Alert must keep error-only delivery"
    Assert-Matches $directAlert "(?ms)eventMetadata:.*?environment: production" "each Flux Alert must retain production metadata"
}
Assert-MatchCount $kustomizationAlert "(?m)^\s+- kind: Kustomization\r?$" 1 "platform-errors must cover Kustomizations exactly once"
Assert-NoMatches $kustomizationAlert "(?m)^\s+- kind: HelmRelease\r?$" "the filtered Alert must not consume HelmRelease events"
Assert-Matches $kustomizationAlert ([regex]::Escape($admissionEofExclusion)) "Kustomization filtering must use the exact anchored admission EOF expression"
Assert-MatchCount $kustomizationAlert "(?m)^\s+exclusionList:\r?$" 1 "only one exclusion list may exist on the Kustomization Alert"
Assert-MatchCount $helmReleaseAlert "(?m)^\s+- kind: HelmRelease\r?$" 1 "platform-helm-errors must cover HelmReleases exactly once"
Assert-NoMatches $helmReleaseAlert "(?m)^\s+- kind: Kustomization\r?$" "the HelmRelease Alert must not duplicate Kustomization events"
Assert-NoMatches $helmReleaseAlert "(?m)^\s+exclusionList:\r?$" "all HelmRelease errors must remain unfiltered"
```

Add this sample-message matrix after the manifest assertions:

```powershell
$excludedMessages = @(
    'ExternalSecret/monitoring/example dry-run failed: failed calling webhook "validate.externalsecret.external-secrets.io": failed to call webhook: Post "https://external-secrets-webhook.external-secrets.svc/validate": EOF',
    'AuthorizationPolicy/example dry-run failed: failed calling webhook "validation.istio.io": failed to call webhook: Post "https://istiod.istio-system.svc/validate": EOF'
)
$forwardedMessages = @(
    'failed calling webhook "validation.istio.io": failed to call webhook: Post "https://istiod.istio-system.svc/validate": context deadline exceeded',
    'failed calling webhook "validation.istio.io": failed to call webhook: Post "https://istiod.istio-system.svc/validate": connect: connection refused',
    'failed calling webhook "validation.istio.io": failed to call webhook: Post "https://istiod.istio-system.svc/validate": x509: certificate has expired',
    'Kustomization reconciliation failed: serviceaccount is forbidden',
    'failed calling webhook "validation.istio.io": failed to call webhook: Post "http://istiod/validate": EOF',
    'failed calling webhook "validation.istio.io": failed to call webhook: Post "https://istiod/validate": EOF trailing text'
)
foreach ($message in $excludedMessages) {
    if ($message -notmatch $admissionEofExclusion) { throw "[ASSERT] expected admission EOF to be excluded: $message" }
}
foreach ($message in $forwardedMessages) {
    if ($message -match $admissionEofExclusion) { throw "[ASSERT] actionable error must remain immediate: $message" }
}
```

- [ ] **Step 3: Run the focused contract and verify RED**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-flux-notifications.ps1
```

Expected: failure containing `[MISSING] rendered resource Alert/platform-helm-errors`.

- [ ] **Step 4: Partition the Alerts and add the exclusion**

Replace `gitops/infrastructure/notifications/alert.yaml` with these two documents:

```yaml
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
  exclusionList:
    - 'failed calling webhook "[^"]+": failed to call webhook: Post "https://[^"]+": EOF$'
---
apiVersion: notification.toolkit.fluxcd.io/v1beta3
kind: Alert
metadata:
  name: platform-helm-errors
  namespace: flux-system
spec:
  providerRef:
    name: discord
  eventSeverity: error
  eventMetadata:
    environment: production
  eventSources:
    - kind: HelmRelease
      name: "*"
```

- [ ] **Step 5: Run focused and full alerting contracts**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-flux-notifications.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
```

Expected: `Flux notification contracts passed` and `All GitOps alerting contracts passed`.

- [ ] **Step 6: Commit the Stage 2 notification policy**

```powershell
git add -- scripts/test-flux-notifications.ps1 gitops/infrastructure/notifications/alert.yaml
git commit -m "fix(alerting): suppress transient Flux webhook EOF events"
```

### Task 5: Document the final routing and read-only checks

**Files:**
- Modify: `scripts/test-alerting-docs-and-ci.ps1:31-50`
- Modify: `docs/runbooks/discord-alerting.md:54-95`

**Interfaces:**
- Consumes: completed Stage 1 and Stage 2 behavior.
- Produces: operator guidance that distinguishes a suppressed transient event from a sustained NotReady incident.

- [ ] **Step 1: Add failing runbook contracts**

Add:

```powershell
Assert-Matches $runbook "platform-helm-errors" "runbook must verify the unfiltered HelmRelease Alert"
Assert-Matches $runbook "gotk_resource_info" "runbook must document the Kustomization state signal"
Assert-Matches $runbook "FluxKustomizationNotReady" "runbook must document the sustained Flux warning"
Assert-Matches $runbook '(?is)admission.*EOF.*5분|5분.*admission.*EOF' "runbook must explain transient EOF suppression and the five-minute fallback"
Assert-Matches $runbook '(?is)HelmRelease.*즉시' "runbook must state that HelmRelease errors remain immediate"
```

- [ ] **Step 2: Run the docs contract and verify RED**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-alerting-docs-and-ci.ps1
```

Expected: failure stating that the runbook must verify `platform-helm-errors`.

- [ ] **Step 3: Update the runbook**

In the read-only command block, add:

```powershell
kubectl -n flux-system get alert platform-helm-errors
kubectl -n monitoring get deployment kube-prometheus-stack-kube-state-metrics
```

Update the loaded rule list to include `FluxKustomizationNotReady`. Add a routing bullet that states:

- only a Kustomization admission HTTPS webhook error ending exactly in `EOF` skips direct Discord delivery;
- `gotk_resource_info` keeps observing its Ready state;
- a non-suspended `flux-system` Kustomization that remains non-True for five minutes fires `FluxKustomizationNotReady`, delivered about 5.5 to 7.5 minutes after the initial state change;
- timeout, connection-refused, certificate, RBAC, build, health-check, and every HelmRelease error remain immediate;
- production must not be deliberately failed for validation.

- [ ] **Step 4: Run documentation and full alerting contracts**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-alerting-docs-and-ci.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
```

Expected: both commands pass.

- [ ] **Step 5: Commit the operational documentation**

```powershell
git add -- scripts/test-alerting-docs-and-ci.ps1 docs/runbooks/discord-alerting.md
git commit -m "docs(alerting): document sustained Flux warnings"
```

### Task 6: Final Stage 2 verification and rollout

**Files:**
- Verify only; no planned production-file changes.

**Interfaces:**
- Consumes: Tasks 4 and 5 after the Stage 1 runtime gate.
- Produces: a reviewable Stage 2 PR and post-merge evidence without induced failure.

- [ ] **Step 1: Run complete local verification**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
kubectl kustomize gitops/infrastructure/notifications
kubectl kustomize gitops/infrastructure/observability
git diff --check origin/main...HEAD
git status --short
```

Expected: all tests and renders pass, no whitespace errors, and no uncommitted files.

- [ ] **Step 2: Review scope and Secret safety**

```powershell
git diff --stat origin/main...HEAD
git diff origin/main...HEAD -- gitops/infrastructure/notifications scripts/test-flux-notifications.ps1 scripts/test-alerting-docs-and-ci.ps1 docs/runbooks/discord-alerting.md
```

Expected: only Stage 2 notification policy, its contracts, and runbook change; no webhook value, admission policy, network policy, or application file appears.

- [ ] **Step 3: Push and create the Stage 2 PR**

Create a separate PR from `codex/tune-flux-webhook-alerts-stage2`. Include Stage 1 runtime evidence, regex sample-matrix evidence, full test output, and rollback-by-revert. Do not manually reconcile production.

- [ ] **Step 4: Verify natural post-merge behavior**

After normal Flux reconciliation, read only:

```powershell
kubectl -n flux-system get alert platform-errors platform-helm-errors
kubectl -n flux-system get kustomizations
kubectl -n monitoring get prometheusrule platform-actionable-alerts
```

Wait for naturally occurring events. A one-off matching EOF should recover without Discord; a future Kustomization that naturally remains NotReady past five minutes should produce one grouped warning and a resolved notification after recovery. Keep the task open as runtime-observation pending until such evidence occurs.
