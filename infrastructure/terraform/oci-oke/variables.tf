variable "compartment_ocid" {
  description = "OCID of the compartment in which the OKE cluster is created."
  type        = string

  validation {
    condition     = startswith(var.compartment_ocid, "ocid1.")
    error_message = "compartment_ocid must be a valid OCID (starting with 'ocid1.')."
  }
}

variable "cluster_name" {
  description = "Display name of the OKE cluster."
  type        = string
}

variable "cluster_type" {
  description = "OKE cluster tier. BASIC_CLUSTER has no control-plane charge and is required for the free tier."
  type        = string
  default     = "BASIC_CLUSTER"

  validation {
    condition     = contains(["BASIC_CLUSTER", "ENHANCED_CLUSTER"], var.cluster_type)
    error_message = "cluster_type must be BASIC_CLUSTER or ENHANCED_CLUSTER."
  }
}

variable "vcn_id" {
  description = "OCID of the VCN hosting the cluster."
  type        = string
}

variable "api_endpoint_subnet_id" {
  description = "OCID of the (public) subnet for the Kubernetes API endpoint."
  type        = string
}

variable "lb_subnet_id" {
  description = "OCID of the (public) subnet for Kubernetes service load balancers."
  type        = string
}

variable "worker_subnet_id" {
  description = "OCID of the (private) subnet for worker nodes."
  type        = string
}

variable "kubernetes_version" {
  description = "Kubernetes version (e.g. v1.33.1). Defaults to the latest version supported by OKE when null."
  type        = string
  default     = null

  validation {
    condition     = var.kubernetes_version == null || can(regex("^v\\d+\\.\\d+\\.\\d+$", coalesce(var.kubernetes_version, "v0.0.0")))
    error_message = "kubernetes_version must look like 'v1.33.1' (or be null for the latest supported version)."
  }
}

variable "node_shape" {
  description = "Worker node shape. Must be the Ampere A1 flex shape to stay in the Always Free tier."
  type        = string
  default     = "VM.Standard.A1.Flex"

  validation {
    condition     = var.node_shape == "VM.Standard.A1.Flex"
    error_message = "Only VM.Standard.A1.Flex (ARM Ampere A1) is allowed within the Oracle Always Free tier."
  }
}

variable "node_count" {
  description = "Number of worker nodes. Combined with node_ocpus/node_memory_gb it must respect the 2 OCPU / 12 GB Always Free cap."
  type        = number
  default     = 2

  validation {
    condition     = var.node_count >= 1 && floor(var.node_count) == var.node_count
    error_message = "node_count must be a positive integer."
  }
}

variable "node_ocpus" {
  description = "OCPUs per worker node (A1 flex). Total across the pool must not exceed 2."
  type        = number
  default     = 1

  validation {
    condition     = var.node_ocpus >= 1 && var.node_ocpus <= 2
    error_message = "node_ocpus must be between 1 and 2 (Always Free A1 cap is 2 OCPUs total)."
  }
}

variable "node_memory_gb" {
  description = "Memory (GB) per worker node (A1 flex). Total across the pool must not exceed 12."
  type        = number
  default     = 6

  validation {
    condition     = var.node_memory_gb >= 1 && var.node_memory_gb <= 12
    error_message = "node_memory_gb must be between 1 and 12 (Always Free A1 cap is 12 GB total)."
  }
}

variable "boot_volume_size_gb" {
  description = "Boot volume size (GB) per worker node. Total across the pool must stay within the 200 GB Always Free block storage quota."
  type        = number
  default     = 50

  validation {
    condition     = var.boot_volume_size_gb >= 50 && var.boot_volume_size_gb <= 200
    error_message = "boot_volume_size_gb must be between 50 (OCI minimum) and 200."
  }
}

variable "node_image_id" {
  description = "Explicit OCID of the worker node image. Defaults to the newest Oracle Linux aarch64 OKE image when null."
  type        = string
  default     = null
}

variable "ssh_public_key" {
  description = "SSH public key installed on worker nodes (required for the Ansible node-setup playbooks). Null disables SSH key provisioning."
  type        = string
  default     = null
}

variable "pods_cidr" {
  description = "CIDR block for pod IPs. Must not overlap the VCN CIDR."
  type        = string
  default     = "10.244.0.0/16"
}

variable "services_cidr" {
  description = "CIDR block for Kubernetes ClusterIP services. Must not overlap the VCN CIDR."
  type        = string
  default     = "10.96.0.0/16"
}

variable "freeform_tags" {
  description = "Freeform tags applied to all resources."
  type        = map(string)
  default     = {}
}
