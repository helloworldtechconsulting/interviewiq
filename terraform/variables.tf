variable "gcp_project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "gcp_region" {
  description = "GCP Region"
  type        = string
  default     = "asia-south1"
}

variable "environment" {
  description = "Environment (staging/production)"
  type        = string
  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "Environment must be either 'staging' or 'production'."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR block"
  type        = string
  default     = "10.0.0.0/16"
}

variable "subnet_cidr" {
  description = "Subnet CIDR block"
  type        = string
  default     = "10.0.1.0/24"
}

variable "database_instance_name" {
  description = "Cloud SQL instance name"
  type        = string
}

variable "database_name" {
  description = "Database name"
  type        = string
  default     = "interviewiq"
}

variable "database_user" {
  description = "Database user"
  type        = string
  default     = "interviewiq"
}

variable "db_password" {
  description = "Database password"
  type        = string
  sensitive   = true
}

variable "instance_tier" {
  description = "Cloud SQL instance tier (db-f1-micro for staging, db-custom-1-3840 for production)"
  type        = string
}

variable "availability_type" {
  description = "Availability type (ZONAL for staging, REGIONAL for production)"
  type        = string
}

variable "backup_retention_days" {
  description = "Number of days to retain backups"
  type        = number
  default     = 7
}

variable "data_bucket_name" {
  description = "GCS data bucket name"
  type        = string
}

variable "frontend_bucket_name" {
  description = "GCS frontend bucket name"
  type        = string
}

variable "openai_api_key" {
  description = "OpenAI API Key"
  type        = string
  sensitive   = true
}

variable "razorpay_key_id" {
  description = "Razorpay Key ID"
  type        = string
  sensitive   = true
}

variable "razorpay_key_secret" {
  description = "Razorpay Key Secret"
  type        = string
  sensitive   = true
}

variable "razorpay_webhook_secret" {
  description = "Razorpay Webhook Secret"
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "JWT Secret Key"
  type        = string
  sensitive   = true
}

variable "cloud_run_service_name" {
  description = "Cloud Run service name"
  type        = string
  default     = "interviewiq-backend"
}

variable "backend_image_url" {
  description = "Backend Docker image URL"
  type        = string
}

variable "container_port" {
  description = "Container port"
  type        = number
  default     = 8080
}

variable "cloud_run_cpu" {
  description = "Cloud Run CPU allocation (e.g., '1' or '2')"
  type        = string
}

variable "cloud_run_memory" {
  description = "Cloud Run memory allocation (e.g., '1Gi' or '2Gi')"
  type        = string
}

variable "cloud_run_min_instances" {
  description = "Cloud Run minimum instances"
  type        = number
  default     = 1
}

variable "cloud_run_max_instances" {
  description = "Cloud Run maximum instances"
  type        = number
  default     = 10
}

variable "artifact_registry_repository" {
  description = "Artifact Registry repository name"
  type        = string
  default     = "interviewiq-docker"
}

variable "notification_email_address" {
  description = "Email address for alert notifications"
  type        = string
}

variable "alert_cpu_threshold" {
  description = "CPU usage threshold percentage for alerts"
  type        = number
  default     = 80
}

variable "alert_5xx_error_threshold" {
  description = "5xx error rate threshold for alerts"
  type        = number
  default     = 1
}

variable "alert_sql_connections_threshold" {
  description = "SQL connections threshold percentage for alerts"
  type        = number
  default     = 80
}
