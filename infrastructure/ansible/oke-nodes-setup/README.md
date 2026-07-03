# OKE Nodes Setup — Flannel to Cilium CNI Cutover

The OKE cluster is created with `cni_type = FLANNEL_OVERLAY` (see
`infrastructure/terraform/oci-oke`). Cilium is installed by GitOps with
`cni.exclusive=false` so it coexists during the transition; this playbook
finishes the cutover on each worker node.

## Order of operations

1. Wait until FluxCD reports `infra-cilium` healthy
   (`flux get kustomizations`) and the Cilium DaemonSet is Ready.
2. Remove the Flannel DaemonSet so it stops rewriting its CNI config:

   ```sh
   kubectl -n kube-system delete daemonset kube-flannel-ds
   ```

3. Copy `inventory.ini.example` to `inventory.ini` (gitignored) and fill
   in the worker IPs, then run:

   ```sh
   ansible-playbook -i inventory.ini playbook.yml
   ```

4. Restart existing pods so they are re-attached through Cilium:

   ```sh
   kubectl get pods -A -o wide   # anything created before the cutover
   kubectl -n <ns> rollout restart deployment/<name>
   ```

Re-run steps 3–4 whenever the node pool adds or recycles a node (new
nodes boot with Flannel's config again).
