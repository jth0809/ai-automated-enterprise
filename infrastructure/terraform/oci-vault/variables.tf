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

variable "atp_wallet_content_base64" {
  description = "Base64-encoded ATP wallet to store as the 'backend-atp-wallet' secret. Skipped when null."
  type        = string
  default     = null
  sensitive   = true
}

variable "freeform_tags" {
  description = "Freeform tags applied to all resources."
  type        = map(string)
  default     = {}
}
