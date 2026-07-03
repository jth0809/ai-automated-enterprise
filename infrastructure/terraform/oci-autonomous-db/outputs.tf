output "autonomous_database_id" {
  description = "OCID of the Autonomous Database."
  value       = oci_database_autonomous_database.this.id
}

output "db_name" {
  description = "Database name."
  value       = oci_database_autonomous_database.this.db_name
}

output "connection_strings" {
  description = "Connection descriptors (high/medium/low/tp/tpurgent profiles)."
  value       = oci_database_autonomous_database.this.connection_strings
}

output "service_console_url" {
  description = "URL of the Autonomous Database service console."
  value       = oci_database_autonomous_database.this.service_console_url
}

output "admin_password" {
  description = "ADMIN user password. Store it in OCI Vault; do not commit it anywhere."
  value       = local.admin_password
  sensitive   = true
}

output "wallet_content_base64" {
  description = "Base64-encoded connection wallet (null when generate_wallet = false)."
  value       = try(oci_database_autonomous_database_wallet.this[0].content, null)
  sensitive   = true
}

output "wallet_password" {
  description = "Password protecting the generated wallet (null when generate_wallet = false)."
  value       = try(random_password.wallet[0].result, null)
  sensitive   = true
}
