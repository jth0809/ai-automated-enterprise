terraform {
  required_version = ">= 1.5.0"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 6.0.0, < 8.0.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.6.0"
    }
  }

  # Remote state (recommended before any team usage).
  # Native "oci" backend requires Terraform >= 1.12; alternatively use an
  # S3-compatible backend against OCI Object Storage.
  #
  # backend "oci" {
  #   bucket    = "terraform-state"
  #   namespace = "<object-storage-namespace>"
  #   key       = "ai-automated-enterprise/phase1.tfstate"
  #   region    = "ap-seoul-1"
  # }
}

# Authentication: either set the four API-key variables below, or leave them
# null to fall back to the DEFAULT profile in ~/.oci/config (or OCI env vars).
provider "oci" {
  tenancy_ocid     = var.tenancy_ocid
  user_ocid        = var.user_ocid
  fingerprint      = var.fingerprint
  private_key_path = var.private_key_path
  region           = var.region
}
