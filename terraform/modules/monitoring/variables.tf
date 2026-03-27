variable "gcp_project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "environment" {
  description = "Environment name"
  type        = string
}

variable "cloud_run_service_url" {
  description = "Cloud Run service URL"
  type        = string
}

variable "notification_email_address" {
  description = "Email address for alerts"
  type        = string
}

variable "alert_cpu_threshold" {
  description = "CPU threshold for alerts (%)"
  type        = number
  default     = 80
}

variable "alert_5xx_error_threshold" {
  description = "5xx error rate threshold"
  type        = number
  default     = 1
}

variable "alert_sql_connections_threshold" {
  description = "SQL connections threshold"
  type        = number
  default     = 80
}
