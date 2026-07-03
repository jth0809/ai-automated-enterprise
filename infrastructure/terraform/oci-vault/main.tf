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

locals {
  # TP profile with SERVER authentication (walletless one-way TLS).
  # try() -> null instead of an index crash when no such profile exists
  # (i.e. while the ATP still has mTLS enforced).
  walletless_profile = try([
    for p in var.atp_connection_strings[0].profiles : p.value
    if p.tls_authentication == "SERVER" && p.consumer_group == "TP"
  ][0], null)
}

resource "oci_vault_secret" "app_jdbc_url" {
  compartment_id = var.compartment_ocid
  vault_id       = oci_kms_vault.this.id
  key_id         = oci_kms_key.this.id
  secret_name    = "backend-atp-jdbc-url"
  description    = "ATP JDBC URL (One-way TLS)"

  secret_content {
    content_type = "BASE64"
    content      = base64encode("jdbc:oracle:thin:@${local.walletless_profile}")
  }

  freeform_tags = var.freeform_tags

  lifecycle {
    # Deliberately a hard, actionable error instead of a count guard:
    # count on a computed value breaks plans mid-migration.
    precondition {
      condition     = local.walletless_profile != null
      error_message = "No walletless (SERVER/TP) ATP connection profile found. Disable mTLS first via the two-step apply: 1) terraform apply -var=\"atp_mtls_required=true\" 2) terraform apply."
    }
  }
}

# TODO(least-privilege): ADMIN is a temporary stopgap. Once a dedicated app
# schema user is created in ATP (SQL, not Terraform), update only the values
# of this secret and app_password — the secret names stay stable, so the
# cluster-side ExternalSecret needs no change.
resource "oci_vault_secret" "app_username" {
  compartment_id = var.compartment_ocid
  vault_id       = oci_kms_vault.this.id
  key_id         = oci_kms_key.this.id
  secret_name    = "backend-atp-username"
  description    = "ATP App Username"

  secret_content {
    content_type = "BASE64"
    content      = base64encode("ADMIN")
  }

  freeform_tags = var.freeform_tags
}

resource "oci_vault_secret" "app_password" {
  compartment_id = var.compartment_ocid
  vault_id       = oci_kms_vault.this.id
  key_id         = oci_kms_key.this.id
  secret_name    = "backend-atp-password"
  description    = "ATP App Password"

  secret_content {
    content_type = "BASE64"
    content      = base64encode(var.atp_admin_password)
  }

  freeform_tags = var.freeform_tags
}

# ------------------------------------------------------------------------------
# OCIR image pull secret (dockerconfigjson consumed by the ocir-pull-secret
# ExternalSecrets in the backend/frontend namespaces). Reuses the existing
# auth token (set ocir_username + ocir_auth_token in terraform.tfvars) instead
# of minting one via oci_identity_auth_token: no home-region IAM writes and
# no risk of hitting the 2-tokens-per-user limit.
# ------------------------------------------------------------------------------
data "oci_objectstorage_namespace" "this" {
  compartment_id = var.compartment_ocid
}

locals {
  ocir_enabled  = var.ocir_auth_token != null && var.ocir_username != null
  ocir_registry = "${var.region}.ocir.io"

  # Locals are evaluated during plan even when only a count=0 resource
  # references them, so every branch must be null-safe.
  ocir_login = local.ocir_enabled ? "${data.oci_objectstorage_namespace.this.namespace}/${var.ocir_username}" : null

  dockerconfigjson = local.ocir_enabled ? jsonencode({
    auths = {
      (local.ocir_registry) = {
        username = local.ocir_login
        password = var.ocir_auth_token
        auth     = base64encode("${local.ocir_login}:${var.ocir_auth_token}")
      }
    }
  }) : null
}

resource "oci_vault_secret" "ocir_dockerconfigjson" {
  count = local.ocir_enabled ? 1 : 0

  compartment_id = var.compartment_ocid
  vault_id       = oci_kms_vault.this.id
  key_id         = oci_kms_key.this.id
  secret_name    = "ocir-dockerconfigjson"
  description    = "Docker config JSON for pulling app images from OCIR."

  secret_content {
    content_type = "BASE64"
    content      = base64encode(local.dockerconfigjson)
  }

  freeform_tags = var.freeform_tags
}
