# =============================================================================
# THE PLATFORM OUTPUT CONTRACT — identical in shape to platform/gcp/outputs.tf
#
# The two modules provision entirely different infrastructure and emit exactly
# the same five things. That identity is the portability property, and it is
# only real because staging actually runs on this module while production runs
# on the other (Arch v4.0 §6).
# =============================================================================

output "kubeconfig" {
  sensitive = true
  value = {
    host                   = oci_containerengine_cluster.primary.endpoints[0].public_endpoint
    cluster_ca_certificate = "" # supplied by the OKE kubeconfig data source at the env layer
  }
}

output "postgres_url" {
  sensitive = true
  value     = "jdbc:postgresql://${oci_psql_db_system.postgres.network_details[0].primary_db_endpoint_private_ip}:5432/interviewiq"
}

output "postgres_username" {
  sensitive = true
  value     = "interviewiq"
}

output "postgres_password" {
  sensitive = true
  value     = random_password.db.result
}

output "object_storage_endpoint" {
  value = var.object_storage_endpoint
}

output "object_storage_bucket" {
  value = var.object_storage_bucket
}

output "object_storage_region" {
  value = "auto"
}

output "object_storage_access_key" {
  sensitive = true
  value     = var.object_storage_access_key
}

output "object_storage_secret_key" {
  sensitive = true
  value     = var.object_storage_secret_key
}

output "secret_store_name" {
  value = "oci-vault"
}

output "cluster_id" {
  description = "OKE cluster OCID, for the kubeconfig data source at the env layer."
  value       = oci_containerengine_cluster.primary.id
}
