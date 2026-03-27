variable "gcp_project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "gcp_region" {
  description = "GCP Region"
  type        = string
  default     = "asia-south1"
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
  default     = "interviewiq-backend-production"
}

variable "backend_image_url" {
  description = "Backend Docker image URL"
  type        = string
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
