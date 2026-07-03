variable "compartment_ocid" {
  description = "OCID of the compartment in which the Autonomous Database is created."
  type        = string

  validation {
    condition     = startswith(var.compartment_ocid, "ocid1.")
    error_message = "compartment_ocid must be a valid OCID (starting with 'ocid1.')."
  }
}

variable "db_name" {
  description = "Database name (max 14 alphanumeric characters, starts with a letter, unique per region/tenancy)."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z][A-Za-z0-9]{0,13}$", var.db_name))
    error_message = "db_name must start with a letter and contain at most 14 alphanumeric characters."
  }
}

variable "display_name" {
  description = "Display name of the Autonomous Database."
  type        = string
}

variable "db_version" {
  description = "Oracle Database version."
  type        = string
  default     = "23ai"

  validation {
    condition     = contains(["19c", "23ai"], var.db_version)
    error_message = "db_version must be '19c' or '23ai'."
  }
}

variable "admin_password" {
  description = "ADMIN user password (12-30 chars, upper/lower/numeric, no double quotes, must not contain 'admin'). A strong password is generated when null."
  type        = string
  default     = null
  sensitive   = true

  validation {
    condition = var.admin_password == null ? true : (
      length(var.admin_password) >= 12 &&
      length(var.admin_password) <= 30 &&
      can(regex("[A-Z]", var.admin_password)) &&
      can(regex("[a-z]", var.admin_password)) &&
      can(regex("[0-9]", var.admin_password)) &&
      !can(regex("\"", var.admin_password)) &&
      !can(regex("(?i)admin", var.admin_password))
    )
    error_message = "admin_password must be 12-30 characters with upper, lower and numeric characters, must not contain double quotes or the word 'admin'."
  }
}

variable "is_mtls_connection_required" {
  description = "Require mutual TLS (wallet-based) connections. Keep true for zero-trust posture."
  type        = bool
  default     = true
}

variable "whitelisted_ips" {
  description = "Optional IP/CIDR access control list for the database. Empty list allows secure access from everywhere (mTLS still enforced)."
  type        = list(string)
  default     = []
}

variable "generate_wallet" {
  description = "Generate a connection wallet and expose it (base64) as a sensitive output."
  type        = bool
  default     = true
}

variable "freeform_tags" {
  description = "Freeform tags applied to all resources."
  type        = map(string)
  default     = {}
}
