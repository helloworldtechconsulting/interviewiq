variable "environment" { type = string }
variable "compartment_id" { type = string }
variable "availability_domain" { type = string }

variable "kubernetes_version" {
  type    = string
  default = "v1.30.1"
}

variable "node_count" {
  type    = number
  default = 2
}

# The Always Free allocation is 4 Ampere cores and 24 GB total, so two nodes at
# 2 OCPU / 12 GB each fits exactly within it (Arch v4.0 §6).
variable "node_ocpus" {
  type    = number
  default = 2
}

variable "node_memory_gb" {
  type    = number
  default = 12
}

variable "node_image_id" {
  description = "Oracle Linux ARM64 image OCID for the chosen region."
  type        = string
}

variable "db_shape" {
  type    = string
  default = "PostgreSQL.VM.Standard.E4.Flex.2.32GB"
}

variable "db_ocpus" {
  type    = number
  default = 2
}

variable "db_memory_gb" {
  type    = number
  default = 32
}

# Cloudflare R2 on every cloud — see the GCP module for the reasoning.
variable "object_storage_endpoint" { type = string }
variable "object_storage_bucket" { type = string }

variable "object_storage_access_key" {
  type      = string
  sensitive = true
}

variable "object_storage_secret_key" {
  type      = string
  sensitive = true
}
