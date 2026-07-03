# ==============================================================================
# Phase 1: Infrastructure Provisioning (Oracle Cloud - Always Free)
#
# Assembles the three modules:
#   oci-network       -> VCN, public/private subnets, gateways, security lists
#   oci-oke           -> OKE cluster + ARM (Ampere A1) node pool (2 OCPU / 12 GB cap)
#   oci-autonomous-db -> Always Free Autonomous Transaction Processing (ATP)
# ==============================================================================

locals {
  name_prefix = "${var.project_name}-${var.environment}"

  common_tags = {
    "project"     = var.project_name
    "environment" = var.environment
    "managed-by"  = "terraform"
    "phase"       = "1-infrastructure"
  }
}

module "network" {
  source = "./oci-network"

  compartment_ocid    = var.compartment_ocid
  name_prefix         = local.name_prefix
  vcn_cidr            = var.vcn_cidr
  public_subnet_cidr  = var.public_subnet_cidr
  private_subnet_cidr = var.private_subnet_cidr
  api_allowed_cidrs   = var.api_allowed_cidrs
  ssh_allowed_cidr    = var.ssh_allowed_cidr
  freeform_tags       = local.common_tags
}

module "oke" {
  source = "./oci-oke"

  compartment_ocid       = var.compartment_ocid
  cluster_name           = "${local.name_prefix}-oke"
  vcn_id                 = module.network.vcn_id
  api_endpoint_subnet_id = module.network.public_subnet_id
  lb_subnet_id           = module.network.public_subnet_id
  worker_subnet_id       = module.network.private_subnet_id

  kubernetes_version  = var.kubernetes_version
  node_count          = var.oke_node_count
  node_ocpus          = var.oke_node_ocpus
  node_memory_gb      = var.oke_node_memory_gb
  boot_volume_size_gb = var.oke_boot_volume_size_gb
  ssh_public_key      = var.ssh_public_key

  freeform_tags = local.common_tags
}

module "autonomous_db" {
  source = "./oci-autonomous-db"

  compartment_ocid            = var.compartment_ocid
  db_name                     = var.atp_db_name
  display_name                = "${local.name_prefix}-atp"
  admin_password              = var.atp_admin_password
  whitelisted_ips             = concat(var.atp_whitelisted_ips, [module.network.vcn_id])
  is_mtls_connection_required = var.atp_mtls_required
  generate_wallet             = false
  freeform_tags               = local.common_tags
}

module "vault" {
  source = "./oci-vault"

  compartment_ocid       = var.compartment_ocid
  vault_display_name     = "${local.name_prefix}-vault"
  atp_admin_password     = module.autonomous_db.admin_password
  atp_connection_strings = module.autonomous_db.connection_strings
  region                 = var.region
  ocir_username          = var.ocir_username
  ocir_auth_token        = var.ocir_auth_token
  freeform_tags          = local.common_tags
}
