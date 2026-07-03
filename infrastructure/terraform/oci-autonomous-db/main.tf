terraform {
  required_version = ">= 1.5.0"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 6.0.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.6.0"
    }
  }
}

# ------------------------------------------------------------------------------
# Admin password: caller-provided, or generated and kept only in state.
# ATP rules: 12-30 chars, upper + lower + numeric, no double quotes, no "admin".
# ------------------------------------------------------------------------------
resource "random_password" "admin" {
  count = var.admin_password == null ? 1 : 0

  length           = 20
  special          = true
  override_special = "#_-"
  min_upper        = 2
  min_lower        = 2
  min_numeric      = 2
  min_special      = 1
}

locals {
  admin_password = var.admin_password != null ? var.admin_password : random_password.admin[0].result
}

# ------------------------------------------------------------------------------
# Always Free Autonomous Transaction Processing (ATP)
# is_free_tier pins the instance to 1 OCPU / 20 GB with no billing.
# ------------------------------------------------------------------------------
resource "oci_database_autonomous_database" "this" {
  compartment_id = var.compartment_ocid
  db_name        = var.db_name
  display_name   = var.display_name
  db_workload    = "OLTP"
  db_version     = var.db_version

  is_free_tier = true
  # cpu_core_count           = 1
  # data_storage_size_in_tbs = 1
  license_model           = "LICENSE_INCLUDED"
  is_auto_scaling_enabled = false

  admin_password = local.admin_password

  # Free-tier ATP is reachable only over TLS; mTLS + wallet is the strictest mode.
  is_mtls_connection_required = var.is_mtls_connection_required
  whitelisted_ips             = length(var.whitelisted_ips) > 0 ? var.whitelisted_ips : null

  freeform_tags = var.freeform_tags

  lifecycle {
    # Password rotation is handled out-of-band (OCI Vault, Phase 2+);
    # re-applies must not reset a rotated password.
    ignore_changes = [admin_password]
  }
}

# ------------------------------------------------------------------------------
# Connection wallet for applications (base64; inject via External Secrets later).
# ------------------------------------------------------------------------------
resource "random_password" "wallet" {
  count = var.generate_wallet ? 1 : 0

  length           = 20
  special          = true
  override_special = "#_-"
  min_upper        = 2
  min_lower        = 2
  min_numeric      = 2
}

resource "oci_database_autonomous_database_wallet" "this" {
  count = var.generate_wallet ? 1 : 0

  autonomous_database_id = oci_database_autonomous_database.this.id
  password               = random_password.wallet[0].result
  generate_type          = "SINGLE"
  base64_encode_content  = true
}
