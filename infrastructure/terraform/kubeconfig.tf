data "oci_containerengine_cluster_kube_config" "this" {
  cluster_id = module.oke.cluster_id
}

resource "local_sensitive_file" "kubeconfig" {
  content  = data.oci_containerengine_cluster_kube_config.this.content
  filename = "${path.module}/kubeconfig"
}
