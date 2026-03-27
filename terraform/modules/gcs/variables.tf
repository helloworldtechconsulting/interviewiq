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

variable "data_bucket" {
  description = "GCS data bucket name"
  type        = string
}

variable "frontend_bucket" {
  description = "GCS frontend bucket name"
  type        = string
}

variable "project_id" {
  description = "GCP Project ID (for compatibility)"
  type        = string
}
