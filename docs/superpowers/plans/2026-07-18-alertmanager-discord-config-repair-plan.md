# Alertmanager Discord Configuration Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Do not dispatch subagents.

**Goal:** Restore the single Alertmanager replica with a Vault-backed Discord receiver and make the repository README and runbook accurately describe the deployed alerting architecture and remaining delivery-verification gate.

**Architecture:** A namespaced `AlertmanagerConfig` owns routes, inhibition rules, and receivers. The Helm-managed `Alertmanager` selects that resource as its global configuration, which avoids namespace matcher injection and lets the Discord receiver use a Kubernetes `SecretKeySelector` instead of an unsupported file field. Static PowerShell contracts guard the rendered GitOps bundle and documentation before Flux is allowed to reconcile production.

**Tech Stack:** Kubernetes, Prometheus Operator 0.83.0 CRDs, kube-prometheus-stack 75.x, FluxCD, External Secrets Operator, OCI Vault, PowerShell contract tests, Kustomize

## Global Constraints

- GitOps is the only production mutation path; never run `kubectl apply` or manually create a production Secret.
- Keep the Discord webhook solely in OCI Vault key `DISCORD_ALERTS_WEBHOOK_URL` and Kubernetes Secret `monitoring/alertmanager-discord-webhook`, key `address`.
- Keep exactly one Alertmanager replica with requests `cpu: 10m`, `memory: 32Mi` and limit `memory: 128Mi`.
- Preserve alert grouping, inhibition, Watchdog suppression, `sendResolved: true`, Cilium egress restrictions, and Istio authorization.
- Do not modify the separate backend ExternalSecret, local OCI permissions, Loki, email routing, or application notification code.
- Do not claim live Discord delivery until a natural non-production or real actionable event verifies it.
- Do not dispatch subagents.

---

## File map

- `gitops/infrastructure/observability/alertmanager-config.yaml`: sole reviewable owner of Alertmanager routes, inhibition rules, and Discord Secret reference.
- `gitops/infrastructure/observability/release.yaml`: HelmRelease resource budget and global `AlertmanagerConfig` selection only; no Discord endpoint configuration.
- `gitops/infrastructure/observability/kustomization.yaml`: includes the new configuration resource in the observability bundle.
- `scripts/test-runtime-alerting.ps1`: rendered-manifest contracts for the global config, Secret selector, routing, security, and resource budget.
- `scripts/test-alerting-docs-and-ci.ps1`: documentation contracts for README and runbook status accuracy.
- `docs/runbooks/discord-alerting.md`: operator provisioning and read-only runtime checks.
- `README.md`: high-level alerting architecture, status commands, and honest readiness state.

### Task 1: Replace the invalid inline Alertmanager Discord configuration

**Files:**
- Create: `gitops/infrastructure/observability/alertmanager-config.yaml`
- Modify: `gitops/infrastructure/observability/release.yaml:35-80`
- Modify: `gitops/infrastructure/observability/kustomization.yaml:3-11`
- Test: `scripts/test-runtime-alerting.ps1:8-36`

**Interfaces:**
- Consumes: Secret `monitoring/alertmanager-discord-webhook`, key `address`, created by the existing ExternalSecret.
- Produces: global `AlertmanagerConfig/platform-alertmanager` selected by the Helm-managed Alertmanager.

- [ ] **Step 1: Change the runtime contract before the manifests**

Add a rendered-resource lookup beside the existing Alertmanager regex variables:

```powershell
$alertmanagerConfig = Get-RenderedResourceByName -Rendered $observability -Kind "AlertmanagerConfig" -Name "platform-alertmanager"
```

Replace the old file-backed and inline Watchdog assertions with these contracts:

```powershell
Assert-MatchCount $observability "(?m)^kind: AlertmanagerConfig$" 1 "observability must define exactly one AlertmanagerConfig"
Assert-Matches $alertmanagerSpec "(?ms)alertmanagerConfiguration:.*?name: platform-alertmanager" "Alertmanager must select the global configuration"
Assert-Matches $alertmanagerSpec "(?m)^\s+resolveTimeout: 5m$" "the global resolve timeout must be preserved"
Assert-Matches $alertmanagerSpec "(?m)^\s+alertmanagerConfigSelector: null$" "supplemental configuration selection must be disabled"
Assert-Matches $alertmanagerSpec "(?m)^\s+alertmanagerConfigNamespaceSelector: null$" "cross-namespace supplemental selection must be disabled"
Assert-NoMatches $alertmanagerSpec "(?m)^\s+secrets:" "Alertmanager must not mount the webhook as a file"
Assert-Matches $alertmanagerConfig "(?ms)apiURL:.*?key: address.*?name: alertmanager-discord-webhook" "Discord must select the Vault-backed Secret key"
Assert-Matches $alertmanagerConfig "(?m)^\s+sendResolved: true$" "resolved alerts must be delivered"
Assert-Matches $alertmanagerConfig "(?ms)groupBy:.*?- namespace.*?- alertname" "alerts must keep stable grouping"
Assert-Matches $alertmanagerConfig "(?m)^\s+groupWait: 30s$" "notification group wait must remain bounded"
Assert-Matches $alertmanagerConfig "(?m)^\s+groupInterval: 5m$" "notification group interval must remain bounded"
Assert-Matches $alertmanagerConfig "(?m)^\s+repeatInterval: 4h$" "notification repeat interval must remain bounded"
Assert-Matches $alertmanagerConfig '(?ms)routes:.*?matchers:.*?name: alertname.*?value: Watchdog.*?receiver: "null"' "Watchdog must route to null"
Assert-MatchCount $alertmanagerConfig "(?m)^\s+(?:-\s+)?sourceMatch:$" 3 "the three source-based inhibition rules must remain"
Assert-MatchCount $alertmanagerConfig "(?m)^\s+(?:-\s+)?targetMatch:$" 4 "all four inhibition targets must remain"
Assert-Matches $alertmanagerConfig "(?ms)sourceMatch:.*?value: critical.*?targetMatch:.*?value: warning\|info" "critical alerts must inhibit warning and info alerts"
Assert-NoMatches $observability "webhook_url_file|webhookUrlFile" "unsupported file-backed Discord fields must not return"
Assert-NoMatches $observability "https://discord\.com/api/webhooks/" "No Webhook URL may be committed"
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
```

Expected: failure containing `[MISSING] rendered resource AlertmanagerConfig/platform-alertmanager` because the resource does not exist yet.

- [ ] **Step 3: Add the global AlertmanagerConfig**

Create `gitops/infrastructure/observability/alertmanager-config.yaml` with this structure:

```yaml
apiVersion: monitoring.coreos.com/v1alpha1
kind: AlertmanagerConfig
metadata:
  name: platform-alertmanager
  namespace: monitoring
spec:
  route:
    receiver: discord
    groupBy:
      - namespace
      - alertname
    groupWait: 30s
    groupInterval: 5m
    repeatInterval: 4h
    routes:
      - receiver: "null"
        matchers:
          - name: alertname
            matchType: "="
            value: Watchdog
  inhibitRules:
    - sourceMatch:
        - name: severity
          matchType: "="
          value: critical
      targetMatch:
        - name: severity
          matchType: "=~"
          value: warning|info
      equal: [namespace, alertname]
    - sourceMatch:
        - name: severity
          matchType: "="
          value: warning
      targetMatch:
        - name: severity
          matchType: "="
          value: info
      equal: [namespace, alertname]
    - sourceMatch:
        - name: alertname
          matchType: "="
          value: InfoInhibitor
      targetMatch:
        - name: severity
          matchType: "="
          value: info
      equal: [namespace]
    - targetMatch:
        - name: alertname
          matchType: "="
          value: InfoInhibitor
  receivers:
    - name: "null"
    - name: discord
      discordConfigs:
        - apiURL:
            name: alertmanager-discord-webhook
            key: address
          sendResolved: true
```

- [ ] **Step 4: Wire the resource and remove the rejected inline format**

Add this resource to `gitops/infrastructure/observability/kustomization.yaml`:

```yaml
  - alertmanager-config.yaml
```

Delete `alertmanager.config` and `alertmanagerSpec.secrets` from `release.yaml`. Add this under `alertmanager.alertmanagerSpec`:

```yaml
        alertmanagerConfigSelector: null
        alertmanagerConfigNamespaceSelector: null
        alertmanagerConfiguration:
          name: platform-alertmanager
          global:
            resolveTimeout: 5m
```

Do not change the replica, retention, service-account, or resource values.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-runtime-alerting.ps1
```

Expected: `Runtime alerting contracts passed`.

- [ ] **Step 6: Commit the runtime repair**

```powershell
git add -- scripts/test-runtime-alerting.ps1 gitops/infrastructure/observability/alertmanager-config.yaml gitops/infrastructure/observability/release.yaml gitops/infrastructure/observability/kustomization.yaml
git commit -m "fix(gitops): repair Alertmanager Discord config"
```

### Task 2: Bring README and the operations runbook up to date

**Files:**
- Modify: `scripts/test-alerting-docs-and-ci.ps1:3-49`
- Modify: `docs/runbooks/discord-alerting.md:51-67`
- Modify: `README.md:39-47,114-125,155-166`

**Interfaces:**
- Consumes: runtime architecture established by Task 1.
- Produces: operator-facing status and verification instructions that never expose Secret data or overclaim delivery.

- [ ] **Step 1: Add failing documentation contracts**

Read the root README alongside the existing documents:

```powershell
$readme = Read-RepoFile "README.md"
```

Add these assertions:

```powershell
Assert-Matches $readme "AlertmanagerConfig" "README must describe the supported Alertmanager configuration path"
Assert-Matches $readme "DISCORD_ALERTS_WEBHOOK_URL" "README must identify the Vault-backed alert credential"
Assert-Matches $readme "docs/runbooks/discord-alerting\.md" "README must link the alerting runbook"
Assert-Matches $readme "(?is)runtime delivery verification.*pending|실제 전달 검증.*대기" "README must distinguish configuration from delivery verification"
Assert-Matches $runbook "kubectl -n monitoring get alertmanagerconfig platform-alertmanager" "runbook must verify the global AlertmanagerConfig"
Assert-Matches $runbook "(?is)Reconciled=True.*Available=True|Reconciled.*Available" "runbook must define Alertmanager readiness conditions"
```

Include `$readme` in the plaintext webhook scan:

```powershell
$trackedText = $workflow + $requiredSecrets + $backlog + $runbook + $design + $readme
```

- [ ] **Step 2: Run the docs contract and verify RED**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-alerting-docs-and-ci.ps1
```

Expected: failure stating that README must describe `AlertmanagerConfig`.

- [ ] **Step 3: Update the runbook**

In the read-only verification command block, add:

```powershell
kubectl -n monitoring get alertmanagerconfig platform-alertmanager
kubectl -n monitoring get alertmanager kube-prometheus-stack-alertmanager
kubectl -n monitoring get pods -l alertmanager=kube-prometheus-stack-alertmanager
```

Document that:

- `platform-alertmanager` references Secret `alertmanager-discord-webhook/address` through `apiURL`;
- the Alertmanager conditions must become `Reconciled=True` and `Available=True` and exactly one Pod must be Ready;
- operators must never print the Secret value;
- Discord delivery remains pending until a natural non-production or real actionable event is observed.

- [ ] **Step 4: Update the root README**

Make these bounded changes:

- Expand the observability stack row to include one-replica Alertmanager and Vault-backed Discord routing.
- Add a short `### 운영 알림` subsection after `### 상태 확인` explaining Flux, Flagger, and Alertmanager paths, linking `docs/runbooks/discord-alerting.md`, and naming `DISCORD_ALERTS_WEBHOOK_URL` without showing its value.
- Add the read-only Alertmanager and AlertmanagerConfig status commands.
- Add an alerting row to the plan/status table that says GitOps resources are implemented while actual Discord delivery verification is pending.

- [ ] **Step 5: Run documentation and full alerting contracts**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-alerting-docs-and-ci.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
```

Expected:

```text
Alerting documentation and CI contracts passed
All GitOps alerting contracts passed
```

- [ ] **Step 6: Commit documentation and its contract**

```powershell
git add -- README.md docs/runbooks/discord-alerting.md scripts/test-alerting-docs-and-ci.ps1
git commit -m "docs(alerting): document repaired Discord routing"
```

### Task 3: Final static and branch verification

**Files:**
- Verify only; no planned production-file changes.

**Interfaces:**
- Consumes: commits from Tasks 1 and 2.
- Produces: evidence suitable for PR review and a post-merge Flux readiness checklist.

- [ ] **Step 1: Run the complete alerting suite from a clean shell**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-gitops-alerting.ps1
```

Expected: all four component contract messages and `All GitOps alerting contracts passed`.

- [ ] **Step 2: Render the observability bundle independently**

```powershell
kubectl kustomize gitops/infrastructure/observability
```

Expected: exit code 0; output contains `kind: AlertmanagerConfig`, `name: platform-alertmanager`, and no real Discord URL.

- [ ] **Step 3: Check patch hygiene and repository state**

```powershell
git diff --check origin/main...HEAD
git status --short
git log --oneline origin/main..HEAD
```

Expected: no whitespace errors, no uncommitted files, and exactly the design, implementation-plan, runtime-repair, and documentation commits.

- [ ] **Step 4: Review the final diff for scope and Secret safety**

```powershell
git diff --stat origin/main...HEAD
git diff origin/main...HEAD -- README.md docs/runbooks/discord-alerting.md gitops/infrastructure/observability scripts
```

Expected: only the approved alerting files and design/plan documents change; no webhook value, backend ExternalSecret, OCI permissions, Loki, or application notifier changes appear.

- [ ] **Step 5: Prepare the PR and post-merge read-only checklist**

Push `codex/fix-alertmanager-discord-config` and create a PR describing the parser failure, Secret-selector repair, test evidence, and rollback-by-revert. Do not manually reconcile production. After merge and normal Flux synchronization, run only the read-only commands documented in the runbook and report readiness separately from Discord delivery verification.
