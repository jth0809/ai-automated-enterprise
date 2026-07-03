output "vcn_id" {
  description = "OCID of the VCN."
  value       = oci_core_vcn.this.id
}

output "vcn_cidr" {
  description = "CIDR block of the VCN."
  value       = var.vcn_cidr
}

output "public_subnet_id" {
  description = "OCID of the public subnet (Kubernetes API endpoint + service load balancers)."
  value       = oci_core_subnet.public.id
}

output "public_subnet_cidr" {
  description = "CIDR block of the public subnet."
  value       = var.public_subnet_cidr
}

output "private_subnet_id" {
  description = "OCID of the private subnet (OKE worker nodes)."
  value       = oci_core_subnet.private.id
}

output "private_subnet_cidr" {
  description = "CIDR block of the private subnet."
  value       = var.private_subnet_cidr
}

output "internet_gateway_id" {
  description = "OCID of the Internet Gateway."
  value       = oci_core_internet_gateway.this.id
}

output "nat_gateway_id" {
  description = "OCID of the NAT Gateway."
  value       = oci_core_nat_gateway.this.id
}

output "service_gateway_id" {
  description = "OCID of the Service Gateway."
  value       = oci_core_service_gateway.this.id
}

output "api_endpoint_security_list_id" {
  description = "OCID of the Kubernetes API endpoint security list."
  value       = oci_core_security_list.api_endpoint.id
}

output "load_balancer_security_list_id" {
  description = "OCID of the service load balancer security list."
  value       = oci_core_security_list.load_balancer.id
}

output "workers_security_list_id" {
  description = "OCID of the worker node security list."
  value       = oci_core_security_list.workers.id
}
