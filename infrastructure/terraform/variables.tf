# ------------------------------------------------------------------------------
# Provider authentication (null = fall back to ~/.oci/config or environment)
# ------------------------------------------------------------------------------
variable "tenancy_ocid" {
  description = "OCID of the tenancy. Null falls back to the OCI CLI config file."
  type        = string
  default     = null
}

variable "user_ocid" {
  description = "OCID of the API user. Null falls back to the OCI CLI config file."
  type        = string
  default     = null
}

variable "fingerprint" {
  description = "Fingerprint of the API signing key. Null falls back to the OCI CLI config file."
  type        = string
  default     = null
}

variable "private_key_path" {
  description = "Path to the API signing private key. Null falls back to the OCI CLI config file."
  type        = string
  default     = null
}

variable "region" {
  description = "OCI region identifier (e.g. ap-seoul-1)."
  type        = string
}

# ------------------------------------------------------------------------------
# Global
# ------------------------------------------------------------------------------
variable "compartment_ocid" {
  description = "OCID of the compartment hosting all Phase 1 resources."
  type        = string

  validation {
    condition     = startswith(var.compartment_ocid, "ocid1.")
    error_message = "compartment_ocid must be a valid OCID (starting with 'ocid1.')."
  }
}

variable "project_name" {
  description = "Short project identifier used in resource display names."
  type        = string
  default     = "ai-enterprise"
}

variable "environment" {
  description = "Deployment environment identifier."
  type        = string
  default     = "prod"
}

# ------------------------------------------------------------------------------
# Network
# ------------------------------------------------------------------------------
variable "vcn_cidr" {
  description = "CIDR block of the VCN."
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "CIDR block of the public subnet (K8s API endpoint + load balancers)."
  type        = string
  default     = "10.0.0.0/24"
}

variable "private_subnet_cidr" {
  description = "CIDR block of the private subnet (OKE worker nodes)."
  type        = string
  default     = "10.0.1.0/24"
}

variable "api_allowed_cidrs" {
  description = "CIDR blocks allowed to reach the Kubernetes API server. Restrict to trusted egress IPs in production."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "ssh_allowed_cidr" {
  description = "Optional CIDR allowed to SSH into worker nodes (for Ansible). Null disables SSH ingress."
  type        = string
  default     = null
}

# ------------------------------------------------------------------------------
# OKE
# ------------------------------------------------------------------------------
variable "kubernetes_version" {
  description = "Kubernetes version (e.g. v1.33.1). Null selects the latest supported by OKE."
  type        = string
  default     = null
}

variable "oke_node_count" {
  description = "Number of ARM worker nodes."
  type        = number
  default     = 2
}

variable "oke_node_ocpus" {
  description = "OCPUs per worker node. node_count * ocpus must be <= 2 (Always Free)."
  type        = number
  default     = 1
}

variable "oke_node_memory_gb" {
  description = "Memory (GB) per worker node. node_count * memory must be <= 12 (Always Free)."
  type        = number
  default     = 6
}

variable "oke_boot_volume_size_gb" {
  description = "Boot volume size (GB) per worker node."
  type        = number
  default     = 50
}

variable "ssh_public_key" {
  description = "SSH public key for worker nodes (used by infrastructure/ansible/oke-nodes-setup)."
  type        = string
  default     = null
}

# ------------------------------------------------------------------------------
# Autonomous Database (ATP)
# ------------------------------------------------------------------------------
variable "atp_db_name" {
  description = "ATP database name (max 14 alphanumeric characters)."
  type        = string
  default     = "aientdb"
}

variable "atp_admin_password" {
  description = "ATP ADMIN password. Null generates a strong password (readable via 'terraform output -raw atp_admin_password')."
  type        = string
  default     = null
  sensitive   = true
}

variable "atp_whitelisted_ips" {
  description = "Optional IP/CIDR access control list for ATP. Empty allows secure access from everywhere (mTLS enforced)."
  type        = list(string)
  default     = []
}

variable "atp_mtls_required" {
  description = <<-EOT
    Require mutual TLS (wallet) for ATP connections. The OCI API rejects
    changing the TLS auth type and the ACL in one request, so flipping
    mTLS off is a two-step apply:
      1. terraform apply -var="atp_mtls_required=true"   # ACL change only
      2. terraform apply                                  # mTLS off (default)
  EOT
  type        = bool
  default     = false
}
