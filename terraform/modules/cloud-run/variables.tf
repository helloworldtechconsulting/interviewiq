variable "gcp_project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "gcp_region" {
  description = "GCP Region"
  type        = string
}

variable "environment" {
  description = "Environment name"
  type        = string
}

variable "service_name" {
  description = "Cloud Run service name"
  type        = string
}

variable "image_url" {
  description = "Docker image URL"
  type        = string
}

variable "container_port" {
  description = "Container port"
  type        = number
  default     = 8080
}

variable "cpu" {
  description = "CPU allocation"
  type        = string
}

variable "memory" {
  description = "Memory allocation"
  type        = string
}

variable "min_instances" {
  description = "Minimum number of instances"
  type        = number
  default     = 1
}

variable "max_instances" {
  description = "Maximum number of instances"
  type        = number
  default     = 10
}

variable "vpc_connector_id" {
  description = "VPC Connector ID"
  type        = string
}

variable "cloud_sql_instance_connection" {
  description = "Cloud SQL instance connection name"
  type        = string
}

variable "database_name" {
  description = "Database name"
  type        = string
}

variable "database_user" {
  description = "Database user"
  type        = string
}

variable "gcs_data_bucket" {
  description = "GCS data bucket name"
  type        = string
}

variable "db_password_secret_id" {
  description = "Database password secret ID"
  type        = string
}

variable "openai_api_key_secret_id" {
  description = "OpenAI API Key secret ID"
  type        = string
}

variable "razorpay_key_id_secret_id" {
  description = "Razorpay Key ID secret ID"
  type        = string
}

variable "razorpay_key_secret_secret_id" {
  description = "Razorpay Key Secret secret ID"
  type        = string
}

variable "jwt_secret_id" {
  description = "JWT Secret ID"
  type        = string
}

variable "razorpay_webhook_secret_id" {
  description = "Razorpay Webhook Secret ID"
  type        = string
}

variable "artifact_registry_repository" {
  description = "Artifact Registry repository name"
  type        = string
}
