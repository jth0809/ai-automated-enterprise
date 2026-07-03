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
# Data sources: availability domains, supported Kubernetes versions, node images
# ------------------------------------------------------------------------------
data "oci_identity_availability_domains" "this" {
  compartment_id = var.compartment_ocid
}

data "oci_containerengine_cluster_option" "this" {
  cluster_option_id = "all"
  compartment_id    = var.compartment_ocid
}

data "oci_containerengine_node_pool_option" "this" {
  node_pool_option_id = oci_containerengine_cluster.this.id
  compartment_id      = var.compartment_ocid
}

locals {
  # Latest supported Kubernetes version unless explicitly pinned.
  kubernetes_version = coalesce(
    var.kubernetes_version,
    reverse(sort(data.oci_containerengine_cluster_option.this.kubernetes_versions))[0],
  )
  kubernetes_semver = trimprefix(local.kubernetes_version, "v")

  # ARM (aarch64) OKE images, preferring ones built for the selected K8s version.
  arm_images_for_version = {
    for source in data.oci_containerengine_node_pool_option.this.sources :
    source.source_name => source.image_id
    if length(regexall("aarch64", source.source_name)) > 0 &&
    length(regexall("OKE-${local.kubernetes_semver}", source.source_name)) > 0
  }
  arm_images_any = {
    for source in data.oci_containerengine_node_pool_option.this.sources :
    source.source_name => source.image_id
    if length(regexall("aarch64", source.source_name)) > 0
  }
  arm_image_pool = length(local.arm_images_for_version) > 0 ? local.arm_images_for_version : local.arm_images_any

  node_image_id = var.node_image_id != null ? var.node_image_id : local.arm_image_pool[reverse(sort(keys(local.arm_image_pool)))[0]]

  total_ocpus     = var.node_count * var.node_ocpus
  total_memory_gb = var.node_count * var.node_memory_gb
}

# ------------------------------------------------------------------------------
# OKE cluster (BASIC_CLUSTER: control plane is free of charge)
# Flannel overlay is the bootstrap CNI; Cilium replaces it in Phase 2 via GitOps.
# ------------------------------------------------------------------------------
resource "oci_containerengine_cluster" "this" {
  compartment_id     = var.compartment_ocid
  name               = var.cluster_name
  kubernetes_version = local.kubernetes_version
  vcn_id             = var.vcn_id
  type               = var.cluster_type
  freeform_tags      = var.freeform_tags

  cluster_pod_network_options {
    cni_type = "FLANNEL_OVERLAY"
  }

  endpoint_config {
    is_public_ip_enabled = true
    subnet_id            = var.api_endpoint_subnet_id
  }

  options {
    service_lb_subnet_ids = [var.lb_subnet_id]

    kubernetes_network_config {
      pods_cidr     = var.pods_cidr
      services_cidr = var.services_cidr
    }

    add_ons {
      is_kubernetes_dashboard_enabled = false
      is_tiller_enabled               = false
    }
  }
}

# ------------------------------------------------------------------------------
# ARM (Ampere A1) node pool — hard-capped at the Always Free envelope.
# ------------------------------------------------------------------------------
resource "oci_containerengine_node_pool" "this" {
  cluster_id         = oci_containerengine_cluster.this.id
  compartment_id     = var.compartment_ocid
  name               = "${var.cluster_name}-pool-arm"
  kubernetes_version = local.kubernetes_version
  node_shape         = var.node_shape
  ssh_public_key     = var.ssh_public_key
  freeform_tags      = var.freeform_tags

  node_shape_config {
    ocpus         = var.node_ocpus
    memory_in_gbs = var.node_memory_gb
  }

  node_source_details {
    source_type             = "IMAGE"
    image_id                = local.node_image_id
    boot_volume_size_in_gbs = var.boot_volume_size_gb
  }

  node_config_details {
    size          = var.node_count
    freeform_tags = var.freeform_tags

    dynamic "placement_configs" {
      for_each = data.oci_identity_availability_domains.this.availability_domains
      content {
        availability_domain = placement_configs.value.name
        subnet_id           = var.worker_subnet_id
      }
    }

    node_pool_pod_network_option_details {
      cni_type = "FLANNEL_OVERLAY"
    }
  }

  initial_node_labels {
    key   = "node.kubernetes.io/instance-family"
    value = "ampere-a1"
  }

  lifecycle {
    precondition {
      condition     = local.total_ocpus <= 2 && local.total_memory_gb <= 12
      error_message = "Oracle Always Free limit exceeded: node_count * node_ocpus must be <= 2 OCPUs (got ${local.total_ocpus}) and node_count * node_memory_gb must be <= 12 GB (got ${local.total_memory_gb})."
    }

    precondition {
      condition     = var.node_count * var.boot_volume_size_gb <= 200
      error_message = "Oracle Always Free limit exceeded: total boot volume storage (node_count * boot_volume_size_gb = ${var.node_count * var.boot_volume_size_gb} GB) must stay within the 200 GB block storage quota."
    }
  }
}
