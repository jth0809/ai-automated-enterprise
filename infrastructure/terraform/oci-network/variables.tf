variable "compartment_ocid" {
  description = "OCID of the compartment in which all network resources are created."
  type        = string

  validation {
    condition     = startswith(var.compartment_ocid, "ocid1.")
    error_message = "compartment_ocid must be a valid OCID (starting with 'ocid1.')."
  }
}

variable "name_prefix" {
  description = "Prefix applied to the display name of every network resource."
  type        = string
}

variable "vcn_cidr" {
  description = "CIDR block of the VCN. Must not overlap with the OKE pods/services CIDRs."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrhost(var.vcn_cidr, 0))
    error_message = "vcn_cidr must be a valid IPv4 CIDR block."
  }
}

variable "vcn_dns_label" {
  description = "DNS label of the VCN (max 15 alphanumeric characters, starts with a letter)."
  type        = string
  default     = "entvcn"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{0,14}$", var.vcn_dns_label))
    error_message = "vcn_dns_label must start with a lowercase letter and contain at most 15 lowercase alphanumeric characters."
  }
}

variable "public_subnet_cidr" {
  description = "CIDR block of the public subnet (Kubernetes API endpoint + service load balancers)."
  type        = string
  default     = "10.0.0.0/24"

  validation {
    condition     = can(cidrhost(var.public_subnet_cidr, 0))
    error_message = "public_subnet_cidr must be a valid IPv4 CIDR block."
  }
}

variable "private_subnet_cidr" {
  description = "CIDR block of the private subnet (OKE worker nodes)."
  type        = string
  default     = "10.0.1.0/24"

  validation {
    condition     = can(cidrhost(var.private_subnet_cidr, 0))
    error_message = "private_subnet_cidr must be a valid IPv4 CIDR block."
  }
}

variable "public_subnet_dns_label" {
  description = "DNS label of the public subnet."
  type        = string
  default     = "pub"
}

variable "private_subnet_dns_label" {
  description = "DNS label of the private subnet."
  type        = string
  default     = "priv"
}

variable "api_allowed_cidrs" {
  description = "CIDR blocks allowed to reach the Kubernetes API server (TCP 6443). Restrict to trusted ranges in production."
  type        = list(string)
  default     = ["0.0.0.0/0"]

  validation {
    condition     = length(var.api_allowed_cidrs) > 0
    error_message = "api_allowed_cidrs must contain at least one CIDR block."
  }
}

variable "ssh_allowed_cidr" {
  description = "Optional CIDR block allowed to SSH into worker nodes (used by Ansible). Set to null to disable SSH ingress entirely."
  type        = string
  default     = null
}

variable "freeform_tags" {
  description = "Freeform tags applied to all resources."
  type        = map(string)
  default     = {}
}
