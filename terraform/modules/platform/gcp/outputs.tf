# =============================================================================
# THE PLATFORM OUTPUT CONTRACT
#
# Arch v4.0 §4: "EVERY one emits the SAME outputs." This file and its
# counterpart in every other platform/ module must stay identical in shape —
# that identity is what makes the workload module cloud-agnostic and what makes
# "changing cloud is changing one variable and re-applying" true rather than
# aspirational.
#
# Adding an output here means adding it to every other platform module too. If
# it cannot be produced everywhere, it does not belong in the contract.
# =============================================================================

output "kubeconfig" {
  description = "Cluster connection details for the kubernetes and helm providers."
  sensitive   = true
  value = {
    host                   = "https://${google_container_cluster.primary.endpoint}"
    cluster_ca_certificate = google_container_cluster.primary.master_auth[0].cluster_ca_certificate
  }
}

output "postgres_url" {
  description = "JDBC URL. Portability is a JDBC URL (Arch v4.0 §3)."
  sensitive   = true
  value       = "jdbc:postgresql://${google_sql_database_instance.postgres.private_ip_address}:5432/${google_sql_database.app.name}"
}

output "postgres_username" {
  sensitive = true
  value     = google_sql_user.app.name
}

output "postgres_password" {
  sensitive = true
  value     = random_password.db.result
}

# Object storage is deliberately NOT provisioned here.
#
# Cloudflare R2 is used on every cloud, because it charges ZERO EGRESS and
# recording playback is the only meaningful egress this product generates
# (Arch v4.0 §3). Keeping storage out of the platform module means it does not
# move when the compute does — the same reasoning that keeps Cloudflare at the
# edge on every cloud.
#
# GCS interop remains available as a fallback and would be added here if R2 were
# ever dropped.
output "object_storage_endpoint" {
  value = var.object_storage_endpoint
}

output "object_storage_bucket" {
  value = var.object_storage_bucket
}

output "object_storage_region" {
  value = "auto" # R2 expects this literal
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
  description = "ClusterSecretStore the External Secrets Operator reads from."
  value       = "gcp-secret-manager"
}
