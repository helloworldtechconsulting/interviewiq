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

variable "database_instance_name" {
  description = "Cloud SQL instance name"
  type        = string
}

variable "database_name" {
  description = "Database name"
  type        = string
}

variable "database_user" {
  description = "Database user name"
  type        = string
}

variable "database_password" {
  description = "Database password"
  type        = string
  sensitive   = true
}

variable "instance_tier" {
  description = "Cloud SQL instance tier"
  type        = string
}

variable "availability_type" {
  description = "Availability type (ZONAL or REGIONAL)"
  type        = string
  validation {
    condition     = contains(["ZONAL", "REGIONAL"], var.availability_type)
    error_message = "Availability type must be ZONAL or REGIONAL."
  }
}

variable "backup_retention_days" {
  description = "Number of backup retained days"
  type        = number
  default     = 7
}

variable "private_network_id" {
  description = "Private network ID for Cloud SQL"
  type        = string
}
