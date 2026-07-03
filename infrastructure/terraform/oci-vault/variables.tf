variable "compartment_ocid" {
  description = "OCID of the compartment in which the Vault is created."
  type        = string

  validation {
    condition     = startswith(var.compartment_ocid, "ocid1.")
    error_message = "compartment_ocid must be a valid OCID (starting with 'ocid1.')."
  }
}

variable "vault_display_name" {
  description = "Display name of the KMS vault."
  type        = string
}

variable "atp_admin_password" {
  description = "ATP ADMIN password to store as the 'backend-atp-admin-password' secret."
  type        = string
  sensitive   = true
}

variable "atp_connection_strings" {
  description = "ATP connection strings (to extract the walletless TCPS profile)."
  type        = any
}

variable "region" {
  description = "OCI region identifier, used to build the OCIR registry host (<region>.ocir.io)."
  type        = string
}

variable "ocir_username" {
  description = <<-EOT
    IAM username for OCIR docker login, WITHOUT the tenancy-namespace prefix
    (federated users include the provider prefix, e.g.
    "oracleidentitycloudservice/user@example.com"). Set together with
    ocir_auth_token; null skips the ocir-dockerconfigjson secret.
  EOT
  type        = string
  default     = null
}

variable "ocir_auth_token" {
  description = "Existing OCI Auth Token for OCIR (same one the CI uses). Null skips the ocir-dockerconfigjson secret."
  type        = string
  default     = null
  sensitive   = true
}

variable "freeform_tags" {
  description = "Freeform tags applied to all resources."
  type        = map(string)
  default     = {}
}
