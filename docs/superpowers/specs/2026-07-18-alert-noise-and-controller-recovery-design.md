# Alert Noise Reduction and Controller Recovery Design

**Date:** 2026-07-18  
**Status:** Approved for implementation  
**Scope:** cert-manager controller, Flux notification-controller, and the global Alertmanager routing policy

## Problem

Alertmanager successfully delivered eight Discord messages immediately after its global configuration became healthy. The messages were not duplicate deliveries: they represented five symptoms from two real controller outages, two cluster-capacity policy warnings, and one informational CPU-throttling group.

The current routing policy amplifies that burst because it groups by both `namespace` and `alertname`, waits only 30 seconds, and repeats unresolved warnings every four hours. At the same time, simply suppressing the CrashLoop, deployment, or target-down alerts would hide real control-plane failures.

## Root-cause evidence

The Kubernetes API Service on the OKE worker node is translated by Cilium as follows:

```text
10.96.0.1:443/TCP -> 10.0.0.73:6443/TCP
```

Both isolated controller policies allow only `toEntities: kube-apiserver` on TCP 443. The realized BPF policy for both failing endpoints shows zero packets against that 443 allow entry.

- `cert-manager` is selected by the restrictive controller CNP while the unselected cainjector and webhook remain healthy. Its previous log reports a TLS handshake timeout/reset while accessing `https://10.96.0.1:443`, and the controller has restarted more than 1,700 times.
- `notification-controller` is excluded from Flux's broad bootstrap egress and selected by its dedicated CNP. It never opens the health endpoint, is killed after three failed probes, and has restarted more than 790 times. The other Flux controllers retain working API access.

The common failure boundary is therefore the post-Service-translation API backend port, not Discord delivery or application memory.

## Goals

1. Restore Kubernetes API access for the two isolated controllers without broadening their destination identity.
2. Keep real critical and warning failures visible.
3. Prevent informational and structurally expected Free Tier warnings from paging Discord.
4. Coalesce related symptoms into incident-oriented messages and reduce repeat noise.
5. Preserve GitOps as the only production mutation path.

## Non-goals

- Do not silence CrashLoop, deployment mismatch, target-down, certificate-expiry, or Flagger failure alerts.
- Do not disable the Prometheus rules that remain useful in Grafana.
- Do not add a second notification service, Pod, or persistent volume.
- Do not manually patch, restart, or apply resources in the production cluster.

## Selected design

### Controller API egress

Keep the existing `kube-apiserver` entity restriction and allow both TCP ports 443 and 6443 in the API egress rule for:

- `gitops/infrastructure/cert-manager/network-policy.yaml`
- `gitops/clusters/production/flux-system/notification-egress.yaml`

Port 6443 matches the observed OKE API backend after Cilium Service translation. Port 443 remains for portability across datapath and cluster implementations that enforce the Service frontend port. No CIDR, `world`, `cluster`, `host`, or unrestricted egress is added.

### Alertmanager routing

The global route keeps Discord as the safe default so that unknown future warnings and critical alerts are not silently discarded.

```text
root: Discord
  group_by: namespace
  group_wait: 2m
  group_interval: 5m
  repeat_interval: 12h

  Watchdog                                      -> null
  severity=info                                 -> null
  KubeCPUOvercommit|KubeMemoryOvercommit        -> null
  severity=critical                             -> Discord (30s wait, 4h repeat)
```

The route order is explicit and child routes do not use `continue`:

1. `Watchdog` remains a receiver-health signal and is never sent to Discord.
2. `severity=info` is explicitly discarded, preventing startup races with `InfoInhibitor` from producing informational pages.
3. The two default overcommit alerts remain queryable in Prometheus and visible in Grafana but do not page. On this intentionally small two-node cluster, they evaluate the ability to lose a whole node rather than immediate saturation; `NodeCPURequestsHigh` remains the actionable pressure alert.
4. Critical alerts override the root timing and retain a 30-second wait and four-hour repeat.
5. Warnings and unknown severities inherit the root's two-minute grouping and twelve-hour repeat.

Existing inhibition rules remain as defense in depth and for any future supplemental receivers.

## Alternatives considered

### Notification-only suppression

This would reduce messages quickly but leave cert-manager and Flux notification delivery broken. It is rejected because it treats symptoms and can conceal certificate-renewal failure.

### Explicit alert allowlist

This produces the smallest message volume but can silently discard a newly introduced critical alert. It is rejected in favor of a Discord default with narrow, reviewable null routes.

### Broad API egress

Allowing `world`, `cluster`, a control-plane CIDR, or all ports would likely recover the controllers but violates least privilege and the repository's zero-trust contract. It is rejected; only the observed API backend port is added to the existing `kube-apiserver` identity.

## Contract tests

Static rendered-manifest contracts must fail before the production manifests are changed and then prove:

- both isolated controller policies allow exactly TCP 443 and 6443 to `kube-apiserver`;
- no additional entity, CIDR, Service, or FQDN destination is introduced;
- the global route groups by `namespace` only;
- root timing is 2 minutes / 5 minutes / 12 hours;
- `Watchdog`, all informational alerts, and only the two named overcommit alerts route to `null`;
- critical alerts retain 30-second grouping and four-hour repetition;
- the Discord receiver remains Vault-backed and `sendResolved: true`;
- all Kustomize and pinned Helm rendering contracts continue to pass.

## GitOps rollout and verification

The changes are committed to a review branch and merged through the normal repository workflow. Flux performs all production reconciliation; no imperative production mutation is permitted.

After Flux applies the commit, read-only verification checks:

1. both Cilium policies are valid and contain the two API ports;
2. cert-manager and notification-controller restart naturally and become Ready;
3. their Deployments regain the desired available replica count;
4. `TargetDown`, CrashLoop, and replica-mismatch alerts resolve;
5. Alertmanager reloads the intended route without errors;
6. info and overcommit groups no longer target Discord;
7. resolution notifications are delivered for the recovered real incidents.

If either controller fails to recover, the rollout stops at diagnosis. The GitOps rollback is a revert of the implementation commit; no manual policy deletion is used.
