output "service_name" {
  description = "Cloud Run service name"
  value       = google_cloud_run_service.backend.name
}

output "service_url" {
  description = "Cloud Run service URL"
  value       = google_cloud_run_service.backend.status[0].url
}

output "service_account_email" {
  description = "Service account email"
  value       = google_service_account.cloud_run.email
}

output "service_account_id" {
  description = "Service account ID"
  value       = google_service_account.cloud_run.unique_id
}
