# Alertmanager Discord Configuration Repair Design

**Date:** 2026-07-18

**Status:** Approved for implementation

**Scope:** Alertmanager runtime configuration, alerting contracts, Discord runbook, root README

## Problem

The `kube-prometheus-stack-alertmanager` resource cannot reconcile and no Alertmanager StatefulSet is created. The Prometheus Operator reports:

```text
field webhook_url_file not found in type alertmanager.discordConfig
```

The Helm values currently place `webhook_url_file` in the generated Alertmanager configuration. The installed Prometheus Operator (0.83.0) rejects that field while parsing the generated Secret, even though the Alertmanager container version itself is newer.

The Discord URL already exists safely as `monitoring/alertmanager-discord-webhook` key `address`, synchronized from OCI Vault by External Secrets Operator. The repair must continue to keep the URL out of Git.

## Goals

- Restore Alertmanager reconciliation and its single resource-constrained replica.
- Reference the existing Vault-backed Kubernetes Secret without embedding or copying the webhook URL into a manifest.
- Preserve alert grouping, inhibition, Watchdog suppression, and resolved notifications.
- Keep GitOps as the only production mutation path.
- Update the root README and Discord runbook so they describe the implemented architecture and honest verification state.

## Non-goals

- Repairing the separate `backend/backend-springboot-secrets` ExternalSecret error.
- Changing local OCI CLI file permissions.
- Upgrading kube-prometheus-stack, Prometheus Operator, or Alertmanager.
- Adding Loki, email routing, or direct application breaking-news notifications.
- Generating an artificial production incident solely to test Discord delivery.

## Decision

Create one `monitoring.coreos.com/v1alpha1` `AlertmanagerConfig` in the `monitoring` namespace and select it as the Alertmanager's global configuration through `alertmanagerSpec.alertmanagerConfiguration.name`.

The installed CRD explicitly supports a Discord `apiURL` `SecretKeySelector`. It also documents that a global `AlertmanagerConfig` does not receive the default namespace matcher, so cluster-wide alerts are not accidentally restricted to `namespace=monitoring`.

The configuration will contain:

- the existing 5-minute global resolve timeout;
- root receiver `discord`;
- grouping by `namespace` and `alertname`;
- existing 30-second group wait, 5-minute group interval, and 4-hour repeat interval;
- a child route sending `Watchdog` to a `null` receiver;
- the three existing inhibition rules;
- a Discord receiver whose `apiURL` references Secret `alertmanager-discord-webhook`, key `address`;
- `sendResolved: true`.

The inline Helm `alertmanager.config` block and file-mount-only `alertmanagerSpec.secrets` entry will be removed. The ExternalSecret remains unchanged and the webhook value remains absent from rendered Git content.

The chart's default empty AlertmanagerConfig selectors will be replaced with an explicit supplemental label selector (`alerting.aienterprise.io/role: supplemental`) restricted to the `monitoring` namespace. The global resource intentionally lacks that label, preventing it from also being selected as a supplemental configuration while leaving a controlled extension point for future configs.

## Alternatives rejected

1. **Upgrade the operator/chart.** This broadens the blast radius and creates unrelated CRD and chart-value regression risk on a resource-constrained cluster.
2. **Template a complete `alertmanager.yaml` Secret through External Secrets Operator.** This keeps the value secret but splits Alertmanager configuration ownership between Vault templating and Helm, making review and rollback harder.
3. **Commit `webhook_url`.** Rejected because it would expose a credential and violate repository security rules.

## File changes

- Add `gitops/infrastructure/observability/alertmanager-config.yaml`.
- Register it in `gitops/infrastructure/observability/kustomization.yaml`.
- Replace the invalid inline configuration in `gitops/infrastructure/observability/release.yaml` with the global `AlertmanagerConfig` reference.
- Update `scripts/test-runtime-alerting.ps1` before production files so the old configuration fails the new contract.
- Update `scripts/test-alerting-docs-and-ci.ps1` to require current README alerting documentation.
- Update `.github/workflows/security-scan-iac.yaml` so README changes execute the alerting documentation contract.
- Update `docs/runbooks/discord-alerting.md` with the new resource and read-only verification sequence.
- Update `README.md` with the Vault-backed Flux/Flagger/Alertmanager-to-Discord architecture and explicitly distinguish GitOps configuration from live delivery verification.

## Verification

Static verification must prove:

- the rendered observability bundle contains exactly one intended `AlertmanagerConfig`;
- Alertmanager references it as global configuration;
- Discord `apiURL` selects `alertmanager-discord-webhook/address`;
- neither `webhook_url_file` nor a real Discord webhook URL is tracked;
- the existing Watchdog route, inhibition behavior, resource limits, Cilium policy, and Istio authorization contracts remain intact;
- the complete GitOps alerting contract suite passes;
- `git diff --check` passes.

After merge, Flux performs the only production reconciliation. Read-only runtime verification checks:

- Alertmanager reports `Reconciled=True` and `Available=True`;
- exactly one Alertmanager Pod is Ready;
- the generated configuration references the Discord receiver without exposing the Secret value;
- Flux Provider, Flagger AlertProvider, ExternalSecrets, and PrometheusRule remain healthy.

Discord delivery remains unverified until a natural non-production or real actionable event produces an observable delivery. README and runbook must not claim otherwise.

## Rollback

Rollback is a Git revert. Flux restores the previous manifests; no manual `kubectl apply`, ad-hoc Secret, or plaintext fallback is permitted. Because the previous manifest prevents Alertmanager startup, rollback is only for unexpected incompatibility while a corrected follow-up is prepared.
