output "data_bucket_name" {
  description = "Data bucket name"
  value       = google_storage_bucket.data.name
}

output "data_bucket_url" {
  description = "Data bucket URL"
  value       = "gs://${google_storage_bucket.data.name}"
}

output "frontend_bucket_name" {
  description = "Frontend bucket name"
  value       = google_storage_bucket.frontend.name
}

output "frontend_bucket_url" {
  description = "Frontend bucket website URL"
  value       = "https://${google_storage_bucket.frontend.name}"
}
