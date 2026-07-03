output "vault_id" {
  description = "OCID of the vault (referenced by the External Secrets ClusterSecretStore)."
  value       = oci_kms_vault.this.id
}

output "management_endpoint" {
  description = "KMS management endpoint of the vault."
  value       = oci_kms_vault.this.management_endpoint
}

output "master_key_id" {
  description = "OCID of the AES-256 master encryption key."
  value       = oci_kms_key.this.id
}

output "secret_ids" {
  description = "OCIDs of the created secrets, keyed by secret name."
  value = merge(
    {
      "backend-atp-admin-password" = oci_vault_secret.atp_admin_password.id
      "backend-atp-jdbc-url"       = oci_vault_secret.app_jdbc_url.id
      "backend-atp-username"       = oci_vault_secret.app_username.id
      "backend-atp-password"       = oci_vault_secret.app_password.id
    },
    length(oci_vault_secret.ocir_dockerconfigjson) > 0
    ? { "ocir-dockerconfigjson" = oci_vault_secret.ocir_dockerconfigjson[0].id }
    : {}
  )
}
