terraform {
  required_version = ">= 1.5.0"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 6.0.0"
    }
  }
}

# ------------------------------------------------------------------------------
# OCI Vault (KMS) — Always Free constraints:
#   - vault_type "DEFAULT" (shared partition). VIRTUAL_PRIVATE is billed.
#   - protection_mode "SOFTWARE" for the master key: software-protected key
#     versions are free of charge (HSM versions are limited/billed).
# Note: destroying a vault only *schedules* deletion (7-30 day waiting period).
# ------------------------------------------------------------------------------
resource "oci_kms_vault" "this" {
  compartment_id = var.compartment_ocid
  display_name   = var.vault_display_name
  vault_type     = "DEFAULT"

  freeform_tags = var.freeform_tags
}

resource "oci_kms_key" "this" {
  compartment_id      = var.compartment_ocid
  display_name        = "${var.vault_display_name}-master-key"
  management_endpoint = oci_kms_vault.this.management_endpoint
  protection_mode     = "SOFTWARE"

  key_shape {
    algorithm = "AES"
    length    = 32 # bytes -> AES-256
  }

  freeform_tags = var.freeform_tags
}

# ------------------------------------------------------------------------------
# Secrets. Secret names are the lookup keys used by the External Secrets
# Operator (ClusterSecretStore "oci-vault" in gitops/security/).
# Content updates create new secret versions; previous versions are retained.
# ------------------------------------------------------------------------------
resource "oci_vault_secret" "atp_admin_password" {
  compartment_id = var.compartment_ocid
  vault_id       = oci_kms_vault.this.id
  key_id         = oci_kms_key.this.id
  secret_name    = "backend-atp-admin-password"
  description    = "ATP ADMIN password (provisioned by Terraform, Phase 1)."

  secret_content {
    content_type = "BASE64"
    content      = base64encode(var.atp_admin_password)
  }

  freeform_tags = var.freeform_tags
}

