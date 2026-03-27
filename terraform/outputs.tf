output "cloud_run_service_url" {
  description = "Cloud Run service URL"
  value       = module.cloud_run.service_url
}

output "cloud_run_service_name" {
  description = "Cloud Run service name"
  value       = module.cloud_run.service_name
}

output "cloud_sql_instance_connection_name" {
  description = "Cloud SQL instance connection name"
  value       = module.cloud_sql.instance_connection_name
  sensitive   = true
}

output "cloud_sql_private_ip" {
  description = "Cloud SQL private IP address"
  value       = module.cloud_sql.private_ip_address
  sensitive   = true
}

output "cloud_sql_instance_name" {
  description = "Cloud SQL instance name"
  value       = module.cloud_sql.instance_name
}

output "gcs_data_bucket_name" {
  description = "GCS data bucket name"
  value       = module.gcs.data_bucket_name
}

output "gcs_frontend_bucket_name" {
  description = "GCS frontend bucket name"
  value       = module.gcs.frontend_bucket_name
}

output "gcs_frontend_bucket_url" {
  description = "GCS frontend bucket website URL"
  value       = module.gcs.frontend_bucket_url
}

output "vpc_id" {
  description = "VPC ID"
  value       = module.networking.vpc_id
}

output "vpc_connector_id" {
  description = "VPC Connector ID"
  value       = module.networking.vpc_connector_id
}

output "artifact_registry_repository_url" {
  description = "Artifact Registry repository URL"
  value       = "asia-south1-docker.pkg.dev/${var.gcp_project_id}/${var.artifact_registry_repository}"
}

output "monitoring_uptime_check_id" {
  description = "Monitoring uptime check ID"
  value       = module.monitoring.uptime_check_id
}

output "monitoring_notification_channel_id" {
  description = "Monitoring notification channel ID"
  value       = module.monitoring.notification_channel_id
}
