# ------------------------------------------------------------------------------
# Network
# ------------------------------------------------------------------------------
output "vcn_id" {
  description = "OCID of the VCN."
  value       = module.network.vcn_id
}

output "public_subnet_id" {
  description = "OCID of the public subnet."
  value       = module.network.public_subnet_id
}

output "private_subnet_id" {
  description = "OCID of the private subnet."
  value       = module.network.private_subnet_id
}

# ------------------------------------------------------------------------------
# OKE
# ------------------------------------------------------------------------------
output "oke_cluster_id" {
  description = "OCID of the OKE cluster."
  value       = module.oke.cluster_id
}

output "oke_kubernetes_version" {
  description = "Kubernetes version of the cluster."
  value       = module.oke.kubernetes_version
}

output "oke_public_endpoint" {
  description = "Public Kubernetes API endpoint."
  value       = module.oke.cluster_public_endpoint
}

output "kubeconfig_command" {
  description = "OCI CLI command that writes a kubeconfig for this cluster (Phase 2 FluxCD bootstrap prerequisite)."
  value       = "oci ce cluster create-kubeconfig --cluster-id ${module.oke.cluster_id} --file $HOME/.kube/config --region ${var.region} --token-version 2.0.0 --kube-endpoint PUBLIC_ENDPOINT"
}

# ------------------------------------------------------------------------------
# Autonomous Database
# ------------------------------------------------------------------------------
output "atp_id" {
  description = "OCID of the Autonomous Database."
  value       = module.autonomous_db.autonomous_database_id
}

output "atp_connection_strings" {
  description = "ATP connection descriptors."
  value       = module.autonomous_db.connection_strings
}

output "atp_admin_password" {
  description = "ATP ADMIN password (read with: terraform output -raw atp_admin_password). Move into OCI Vault in Phase 2."
  value       = module.autonomous_db.admin_password
  sensitive   = true
}

output "atp_wallet_content_base64" {
  description = "Base64-encoded ATP wallet for application connectivity (inject via External Secrets Operator in Phase 2)."
  value       = module.autonomous_db.wallet_content_base64
  sensitive   = true
}

output "atp_wallet_password" {
  description = "Password protecting the ATP wallet."
  value       = module.autonomous_db.wallet_password
  sensitive   = true
}

# ------------------------------------------------------------------------------
# Vault
# ------------------------------------------------------------------------------
output "vault_ocid" {
  description = "OCID of the OCI Vault. Paste into gitops/security/external-secrets/cluster-secret-store.yaml (spec.provider.oracle.vault)."
  value       = module.vault.vault_id
}

output "vault_secret_ids" {
  description = "OCIDs of the secrets stored in the vault, keyed by secret name."
  value       = module.vault.secret_ids
}
