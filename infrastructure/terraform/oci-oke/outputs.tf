output "cluster_id" {
  description = "OCID of the OKE cluster."
  value       = oci_containerengine_cluster.this.id
}

output "cluster_name" {
  description = "Display name of the OKE cluster."
  value       = oci_containerengine_cluster.this.name
}

output "kubernetes_version" {
  description = "Kubernetes version actually provisioned."
  value       = oci_containerengine_cluster.this.kubernetes_version
}

output "cluster_public_endpoint" {
  description = "Public Kubernetes API endpoint of the cluster."
  value       = try(oci_containerengine_cluster.this.endpoints[0].public_endpoint, null)
}

output "node_pool_id" {
  description = "OCID of the ARM node pool."
  value       = oci_containerengine_node_pool.this.id
}

output "node_image_id" {
  description = "OCID of the image used for worker nodes."
  value       = local.node_image_id
}

output "total_ocpus" {
  description = "Total OCPUs consumed by the node pool (Always Free cap: 2)."
  value       = local.total_ocpus
}

output "total_memory_gb" {
  description = "Total memory in GB consumed by the node pool (Always Free cap: 12)."
  value       = local.total_memory_gb
}
