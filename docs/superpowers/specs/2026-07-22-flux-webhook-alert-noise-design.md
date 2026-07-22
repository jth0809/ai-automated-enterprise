# Flux Admission Webhook Alert Noise Design

**Date:** 2026-07-22
**Status:** Approved design; written-spec review pending
**Scope:** Flux Kustomization admission-webhook EOF events and sustained reconciliation failure alerts

## Problem

Flux reconciles unchanged Git revisions every ten minutes. On this OKE cluster, a small fraction of Kubernetes API server admission-webhook calls arrive through the managed proxymux path with a malformed TLS first record. External Secrets and Istio reject those connections immediately, and kustomize-controller emits an error such as:

```text
failed calling webhook "...": failed to call webhook: Post "https://...": EOF
```

The normal Flux retry succeeds two minutes later, but the current Flux `Alert` sends every first error directly to Discord. This produces repeated incident-like notifications for self-healing transport failures while genuine manifest, authorization, source, and rollout failures still require immediate attention.

## Evidence and boundary

- Eight user-visible Flux failures match the target webhook TLS rejection and OKE proxymux reset to the same second.
- The failures span External Secrets and Istio admission webhooks, multiple Kustomizations, and both worker-node proxymux paths.
- Both webhook Deployments remain Ready with zero restarts, valid certificates, and successful subsequent reconciliations.
- The customer-controlled boundary is notification policy. The managed control-plane/proxymux defect requires Oracle investigation and is not repaired by this change.

## Goals

1. Do not send Discord notifications for a single admission-webhook EOF that self-recovers.
2. Send a warning when a Kustomization remains NotReady continuously for five minutes.
3. Preserve immediate direct Discord delivery for every non-EOF Kustomization error and every HelmRelease error.
4. Preserve fail-closed admission validation and GitOps-only production mutation.
5. Add no application, controller, persistent volume, external service, or new inter-service scrape path.

## Non-goals

- Do not set admission webhook `failurePolicy` to `Ignore` or disable validation.
- Do not hide timeout, connection-refused, certificate, schema, RBAC, source, build, rollout, or health-check errors.
- Do not suppress HelmRelease or Kustomization errors broadly.
- Do not implement a stateful event-counting service.
- Do not manually reconcile, patch, restart, or apply production resources.

## Selected design

### 1. Narrow direct-event exclusion with source isolation

Keep the existing Flux `Alert` named `platform-errors`, its provider, metadata, and `eventSeverity: error`, but limit its source to Kustomizations. Add one `exclusionList` expression that matches only an HTTPS admission-webhook call whose terminal error is exactly `EOF`:

```yaml
exclusionList:
  - 'failed calling webhook "[^"]+": failed to call webhook: Post "https://[^"]+": EOF$'
```

Create a second Flux `Alert`, `platform-helm-errors`, with the same provider, severity, and metadata but only the existing wildcard HelmRelease source and no exclusion. This source split prevents a Kustomization-specific suppression policy from silently changing HelmRelease delivery.

Flux evaluates exclusions against event message content. A matching Kustomization EOF remains visible in controller logs, Kubernetes events, and resource conditions but is not posted directly to Discord. Other Kustomization errors do not match, and all HelmRelease errors retain immediate delivery.

### 2. Kustomization condition collection through kube-state-metrics

Extend the existing `kube-prometheus-stack` Helm values instead of scraping Flux controllers directly. Enable the kube-state-metrics `customResourceState` configuration for exactly one resource:

- API group `kustomize.toolkit.fluxcd.io`
- version `v1`
- kind `Kustomization`
- metric name prefix `gotk`
- `resource_info` Info metric with `name`, `exported_namespace`, `ready`, and `suspended` labels

The configuration follows Flux's documented `gotk_resource_info` pattern. Add a least-privilege kube-state-metrics RBAC rule granting only `list` and `watch` on `kustomizations.kustomize.toolkit.fluxcd.io`. Keep the chart's existing core collectors enabled; do not set `--custom-resource-state-only`.

This reuses kube-state-metrics' existing authenticated TLS connection to the Kubernetes API and Prometheus' existing kube-state-metrics scrape target. It adds no direct Prometheus-to-Flux plaintext path, NetworkPolicy, AuthorizationPolicy, workload, or storage.

### 3. Sustained NotReady rule

Extend the existing `platform-actionable-alerts` `PrometheusRule` with:

```promql
max by (exported_namespace, name) (
  gotk_resource_info{
    customresource_group="kustomize.toolkit.fluxcd.io",
    customresource_kind="Kustomization",
    exported_namespace="flux-system",
    ready!="True",
    suspended!="true"
  }
) == 1
```

The alert name is `FluxKustomizationNotReady`, severity is `warning`, and `for` is exactly `5m`. `ready!="True"` deliberately includes False, Unknown, and an initially absent Ready condition. `suspended!="true"` excludes intentionally suspended resources while retaining resources whose optional suspend field is absent or false.

With the observed Flux two-minute retry, a five-minute continuous NotReady condition normally spans multiple failed attempts. The durable invariant remains elapsed NotReady time, so a future retry-backoff change cannot accidentally page on the first transient failure.

### 4. Alertmanager delivery timing

Add a dedicated child route for `alertname=FluxKustomizationNotReady` before the generic critical route. It uses the Discord receiver, groups by `alertname` and `exported_namespace`, sets a 30-second `groupWait`, and keeps the existing 12-hour warning repeat interval. This prevents the root warning route from adding another two-minute delay after Prometheus has already enforced the five-minute persistence window and groups simultaneous failures by the Flux resource namespace rather than kube-state-metrics' scrape namespace.

With independent 60-second scrape and evaluation intervals, expected delivery is approximately 5.5 to 7.5 minutes after the condition first becomes false. Related alerts remain grouped by Flux resource namespace. `sendResolved: true` remains unchanged.

## Data flow

```text
single admission EOF
  -> Flux Ready=False + error event
  -> direct Flux Alert excludes only the EOF message
  -> kube-state-metrics exports ready="False"
  -> normal two-minute retry succeeds
  -> Ready=True before five minutes
  -> no Discord notification

sustained reconciliation failure
  -> kube-state-metrics continues exporting ready!="True"
  -> Prometheus condition is true for five minutes
  -> FluxKustomizationNotReady fires
  -> Alertmanager waits 30 seconds and sends Discord warning
  -> recovery sends RESOLVED

non-EOF Flux error
  -> does not match exclusion
  -> existing Flux Provider sends Discord immediately

any HelmRelease error
  -> isolated HelmRelease Alert has no exclusion
  -> existing Flux Provider sends Discord immediately
```

## Failure behavior

- If Prometheus is temporarily unavailable, direct non-EOF Flux notifications continue independently.
- If kube-state-metrics collection fails, its existing target/list/watch health remains observable; direct non-EOF Kustomization and all HelmRelease notifications continue independently.
- If an admission EOF persists, the Kustomization remains false and the sustained rule reports it.
- A malformed exclusion expression must fail contract validation before merge.
- The only coupled failure case is a sustained Kustomization webhook EOF during a simultaneous kube-state-metrics or Prometheus outage. Staged rollout prevents enabling the exclusion before the replacement signal is proven, while the monitoring stack's own health alerts remain the operational fallback.

## Alternatives considered

### Delay all Flux errors

Suppressing all direct events and relying only on NotReady duration would reduce noise but delay deterministic manifest, RBAC, and rollout failures. Rejected because those failures are actionable immediately.

### Provider rate limiting

Flux event rate limiting suppresses duplicate messages after the first delivery. It cannot delay or retract the first notification, so it does not solve this problem.

### Stateful two-attempt aggregator

A custom receiver could count exact consecutive failures. It adds a workload, persistence and availability responsibility for a distinction already represented safely by five minutes of continuous NotReady state. Rejected under the cluster resource budget.

### Direct Flux controller PodMonitor

Flux exposes native controller metrics, but adding a cross-namespace PodMonitor would create a new direct Prometheus-to-Flux HTTP scrape path. The repository's zero-trust policy requires authenticated and encrypted inter-service traffic. Reusing the existing kube-state-metrics path provides the required resource state without widening the network surface.

## Contract tests

Tests must be written and observed failing before production manifests change. They prove:

- the Kustomization and HelmRelease sources are partitioned into exactly two non-overlapping Flux Alerts;
- both direct Alerts retain `eventSeverity: error`, the Discord provider, and production metadata;
- exactly one exclusion expression exists, only on the Kustomization Alert, and it is limited to a quoted HTTPS `failed calling webhook ... EOF` ending at the message boundary;
- timeout, connection-refused, certificate, RBAC, and generic reconciliation messages are not excluded;
- kube-state-metrics keeps its existing core collectors and gains only Kustomization `list`/`watch` RBAC;
- its custom-resource configuration emits the documented `gotk_resource_info` labels for Kustomization `v1` and does not configure other Flux resource kinds;
- no new Flux controller PodMonitor, NetworkPolicy, or AuthorizationPolicy is introduced;
- `FluxKustomizationNotReady` selects non-True, non-suspended Kustomizations in `flux-system` from `gotk_resource_info`, groups by resource identity, and has `for: 5m`;
- its Alertmanager child route groups by alert and exported namespace, uses Discord with a 30-second wait, and repeats after 12 hours;
- all existing null, warning, critical, Vault-secret, Cilium and authorization contracts remain valid;
- Kustomize and pinned Helm rendering succeed and `git diff --check` remains clean.

## GitOps rollout and verification

The implementation is committed to a review branch and merged normally. Flux performs every production mutation. Rollout has two GitOps stages to avoid a notification blind spot:

1. Deploy kube-state-metrics RBAC/configuration, the Prometheus rule, and its Alertmanager route while direct Flux delivery remains unchanged. Verify the metric and loaded rule read-only.
2. Only after stage 1 is healthy, split the Flux Alerts and enable the narrow Kustomization EOF exclusion.

Read-only post-merge verification checks:

1. kube-state-metrics has successful Kustomization list/watch operations and its existing scrape target is healthy.
2. `gotk_resource_info` returns every production Kustomization in `flux-system` with namespace, name, Ready, and suspend state.
3. `FluxKustomizationNotReady` is loaded and inactive while all non-suspended Kustomizations are Ready.
4. `platform-errors` reports the intended single exclusion pattern and only the Kustomization source.
5. `platform-helm-errors` reports the HelmRelease source with no exclusion.
6. A future one-off admission EOF produces no direct Discord message and recovers before the rule threshold.
7. Any Kustomization that remains NotReady beyond five minutes produces one grouped warning and later a resolved notification.

Production is not deliberately broken to test the alert. Verification uses rendered contracts, current healthy metrics, and naturally occurring events.

## References

- [Flux Alert exclusion list](https://fluxcd.io/flux/components/notification/alerts/)
- [Flux resource metrics with kube-state-metrics](https://fluxcd.io/flux/monitoring/metrics/)
- [Flux `gotk_resource_info` custom metric configuration](https://fluxcd.io/flux/monitoring/custom-metrics/)
- [kube-state-metrics custom resource state metrics](https://github.com/kubernetes/kube-state-metrics/blob/main/docs/metrics/extend/customresourcestate-metrics.md)
