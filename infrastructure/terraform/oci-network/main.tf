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
# Oracle Services Network (used by the Service Gateway so private workers can
# reach OCIR, OKE control plane services, and Object Storage without internet).
# ------------------------------------------------------------------------------
data "oci_core_services" "all_services" {
  filter {
    name   = "name"
    values = ["All .* Services In Oracle Services Network"]
    regex  = true
  }
}

# ------------------------------------------------------------------------------
# VCN
# ------------------------------------------------------------------------------
resource "oci_core_vcn" "this" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-vcn"
  cidr_blocks    = [var.vcn_cidr]
  dns_label      = var.vcn_dns_label
  freeform_tags  = var.freeform_tags
}

# ------------------------------------------------------------------------------
# Gateways
# ------------------------------------------------------------------------------
resource "oci_core_internet_gateway" "this" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-igw"
  enabled        = true
  freeform_tags  = var.freeform_tags
}

resource "oci_core_nat_gateway" "this" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-natgw"
  freeform_tags  = var.freeform_tags
}

resource "oci_core_service_gateway" "this" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-sgw"
  freeform_tags  = var.freeform_tags

  services {
    service_id = data.oci_core_services.all_services.services[0].id
  }
}

# ------------------------------------------------------------------------------
# Route tables
# ------------------------------------------------------------------------------
resource "oci_core_route_table" "public" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-rt-public"
  freeform_tags  = var.freeform_tags

  route_rules {
    description       = "Default route to the internet"
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.this.id
  }
}

resource "oci_core_route_table" "private" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-rt-private"
  freeform_tags  = var.freeform_tags

  route_rules {
    description       = "Outbound internet access via NAT (image pulls, package repos)"
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_nat_gateway.this.id
  }

  route_rules {
    description       = "Oracle Services Network via Service Gateway (OCIR, OKE, Object Storage)"
    destination       = data.oci_core_services.all_services.services[0].cidr_block
    destination_type  = "SERVICE_CIDR_BLOCK"
    network_entity_id = oci_core_service_gateway.this.id
  }
}

# ------------------------------------------------------------------------------
# Security list: Kubernetes API endpoint (public subnet)
# Rules follow the official OKE requirements for a public API endpoint with
# private worker nodes (flannel overlay, later replaced in-cluster by Cilium).
# ------------------------------------------------------------------------------
resource "oci_core_security_list" "api_endpoint" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-sl-k8s-api"
  freeform_tags  = var.freeform_tags

  dynamic "ingress_security_rules" {
    for_each = var.api_allowed_cidrs
    content {
      description = "External access to the Kubernetes API server"
      protocol    = "6" # TCP
      source      = ingress_security_rules.value
      source_type = "CIDR_BLOCK"
      stateless   = false

      tcp_options {
        min = 6443
        max = 6443
      }
    }
  }

  ingress_security_rules {
    description = "Worker nodes to Kubernetes API server"
    protocol    = "6"
    source      = var.private_subnet_cidr
    source_type = "CIDR_BLOCK"
    stateless   = false

    tcp_options {
      min = 6443
      max = 6443
    }
  }

  ingress_security_rules {
    description = "Worker nodes to OKE control plane communication"
    protocol    = "6"
    source      = var.private_subnet_cidr
    source_type = "CIDR_BLOCK"
    stateless   = false

    tcp_options {
      min = 12250
      max = 12250
    }
  }

  ingress_security_rules {
    description = "Path MTU discovery from worker nodes"
    protocol    = "1" # ICMP
    source      = var.private_subnet_cidr
    source_type = "CIDR_BLOCK"
    stateless   = false

    icmp_options {
      type = 3
      code = 4
    }
  }

  egress_security_rules {
    description      = "Control plane to worker node kubelet and services"
    protocol         = "6"
    destination      = var.private_subnet_cidr
    destination_type = "CIDR_BLOCK"
    stateless        = false
  }

  egress_security_rules {
    description      = "Path MTU discovery to worker nodes"
    protocol         = "1"
    destination      = var.private_subnet_cidr
    destination_type = "CIDR_BLOCK"
    stateless        = false

    icmp_options {
      type = 3
      code = 4
    }
  }

  egress_security_rules {
    description      = "Control plane to OKE service endpoints (Oracle Services Network)"
    protocol         = "6"
    destination      = data.oci_core_services.all_services.services[0].cidr_block
    destination_type = "SERVICE_CIDR_BLOCK"
    stateless        = false

    tcp_options {
      min = 443
      max = 443
    }
  }
}

# ------------------------------------------------------------------------------
# Security list: service load balancers (public subnet)
# ------------------------------------------------------------------------------
resource "oci_core_security_list" "load_balancer" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-sl-lb"
  freeform_tags  = var.freeform_tags

  ingress_security_rules {
    description = "Public HTTP traffic to service load balancers"
    protocol    = "6"
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    stateless   = false

    tcp_options {
      min = 80
      max = 80
    }
  }

  ingress_security_rules {
    description = "Public HTTPS traffic to service load balancers"
    protocol    = "6"
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    stateless   = false

    tcp_options {
      min = 443
      max = 443
    }
  }

  egress_security_rules {
    description      = "Load balancer to worker node NodePort range"
    protocol         = "6"
    destination      = var.private_subnet_cidr
    destination_type = "CIDR_BLOCK"
    stateless        = false

    tcp_options {
      min = 30000
      max = 32767
    }
  }

  egress_security_rules {
    description      = "Load balancer health checks to kube-proxy"
    protocol         = "6"
    destination      = var.private_subnet_cidr
    destination_type = "CIDR_BLOCK"
    stateless        = false

    tcp_options {
      min = 10256
      max = 10256
    }
  }
}

# ------------------------------------------------------------------------------
# Security list: worker nodes (private subnet)
# ------------------------------------------------------------------------------
resource "oci_core_security_list" "workers" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-sl-workers"
  freeform_tags  = var.freeform_tags

  ingress_security_rules {
    description = "Worker-to-worker and pod-to-pod traffic (all protocols)"
    protocol    = "all"
    source      = var.private_subnet_cidr
    source_type = "CIDR_BLOCK"
    stateless   = false
  }

  ingress_security_rules {
    description = "Control plane and load balancer traffic from the public subnet"
    protocol    = "6"
    source      = var.public_subnet_cidr
    source_type = "CIDR_BLOCK"
    stateless   = false
  }

  ingress_security_rules {
    description = "Path MTU discovery"
    protocol    = "1"
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    stateless   = false

    icmp_options {
      type = 3
      code = 4
    }
  }

  dynamic "ingress_security_rules" {
    for_each = var.ssh_allowed_cidr != null ? [var.ssh_allowed_cidr] : []
    content {
      description = "SSH access to worker nodes (Ansible node setup)"
      protocol    = "6"
      source      = ingress_security_rules.value
      source_type = "CIDR_BLOCK"
      stateless   = false

      tcp_options {
        min = 22
        max = 22
      }
    }
  }

  egress_security_rules {
    description      = "Worker node egress (via NAT and Service Gateway routes)"
    protocol         = "all"
    destination      = "0.0.0.0/0"
    destination_type = "CIDR_BLOCK"
    stateless        = false
  }
}

# ------------------------------------------------------------------------------
# Subnets
# ------------------------------------------------------------------------------
resource "oci_core_subnet" "public" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.this.id
  display_name               = "${var.name_prefix}-subnet-public"
  cidr_block                 = var.public_subnet_cidr
  dns_label                  = var.public_subnet_dns_label
  route_table_id             = oci_core_route_table.public.id
  prohibit_public_ip_on_vnic = false
  freeform_tags              = var.freeform_tags

  security_list_ids = [
    oci_core_security_list.api_endpoint.id,
    oci_core_security_list.load_balancer.id,
  ]
}

resource "oci_core_subnet" "private" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.this.id
  display_name               = "${var.name_prefix}-subnet-private"
  cidr_block                 = var.private_subnet_cidr
  dns_label                  = var.private_subnet_dns_label
  route_table_id             = oci_core_route_table.private.id
  prohibit_public_ip_on_vnic = true
  freeform_tags              = var.freeform_tags

  security_list_ids = [
    oci_core_security_list.workers.id,
  ]
}
