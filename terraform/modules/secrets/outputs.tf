output "db_password_secret_id" {
  description = "Database password secret ID"
  value       = google_secret_manager_secret.db_password.id
}

output "openai_api_key_secret_id" {
  description = "OpenAI API Key secret ID"
  value       = google_secret_manager_secret.openai_api_key.id
}

output "razorpay_key_id_secret_id" {
  description = "Razorpay Key ID secret ID"
  value       = google_secret_manager_secret.razorpay_key_id.id
}

output "razorpay_key_secret_secret_id" {
  description = "Razorpay Key Secret secret ID"
  value       = google_secret_manager_secret.razorpay_key_secret.id
}

output "jwt_secret_id" {
  description = "JWT Secret ID"
  value       = google_secret_manager_secret.jwt_secret.id
}

output "razorpay_webhook_secret_id" {
  description = "Razorpay Webhook Secret ID"
  value       = google_secret_manager_secret.razorpay_webhook_secret.id
}
