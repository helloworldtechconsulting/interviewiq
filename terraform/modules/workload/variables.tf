# =============================================================================
# workload — variables
#
# This module is 100% cloud-agnostic and consumes the fixed output contract that
# EVERY platform module emits (Arch v4.0 §4). It never learns which cloud it is
# on: "changing cloud is changing one variable and re-applying."
#
# If you find yourself adding a cloud-specific variable here, it belongs in the
# platform layer instead — that separation is the whole design.
# =============================================================================

variable "environment" {
  description = "Environment name, used in resource names and labels."
  type        = string
}

# ── The platform output contract ─────────────────────────────────────────────

variable "postgres_url" {
  description = "JDBC URL for the managed PostgreSQL instance. Portability is a JDBC URL."
  type        = string
  sensitive   = true
}

variable "postgres_username" {
  type      = string
  sensitive = true
}

variable "postgres_password" {
  type      = string
  sensitive = true
}

variable "object_storage_endpoint" {
  description = <<-EOT
    S3-compatible API endpoint. Empty for real AWS S3; set for R2, GCS interop,
    Spaces, MinIO or OCI. The application needs nothing else to switch store.
  EOT
  type        = string
  default     = ""
}

variable "object_storage_bucket" {
  type = string
}

variable "object_storage_region" {
  type    = string
  default = "auto"
}

variable "object_storage_access_key" {
  type      = string
  sensitive = true
  default   = ""
}

variable "object_storage_secret_key" {
  type      = string
  sensitive = true
  default   = ""
}

# ── Application ──────────────────────────────────────────────────────────────

variable "image" {
  description = "Container image from GHCR — cloud-neutral, reversing the ECR decision (INTIQ-52)."
  type        = string
}

variable "namespace" {
  type    = string
  default = "interviewiq"
}

variable "api_host" {
  description = "Public API hostname, e.g. api.interviewiq.in."
  type        = string
}

variable "frontend_origins" {
  description = <<-EOT
    Exact origins allowed to call the API. PRD v2.1 §7.1.3 makes an absent or
    permissive CORS policy a launch blocker, and credentials cannot be combined
    with a wildcard origin anyway.
  EOT
  type        = list(string)
}

# ── Scaling (PRD v2.1 §8, Arch v4.0 §5.1) ────────────────────────────────────

variable "web_min_replicas" {
  description = "Floor of 2 for availability across 2 nodes, not for capacity — see Arch v4.0 §0."
  type        = number
  default     = 2
}

variable "web_max_replicas" {
  type    = number
  default = 6
}

variable "worker_min_replicas" {
  type    = number
  default = 1
}

variable "worker_max_replicas" {
  type    = number
  default = 4
}

variable "hikari_pool_size" {
  description = <<-EOT
    HikariCP connections per web pod. Arch v4.0 §5.4: a db.t4g.micro-class
    instance allows ~112 connections, and 6 web x 10 + 4 worker x 6 = 84 leaves
    headroom. Verify with SHOW max_connections after provisioning.
  EOT
  type        = number
  default     = 10
}

variable "bucket_capacity" {
  description = "Concurrent interviews per 5-minute capacity bucket (PRD §7.4.2)."
  type        = number
  default     = 25
}

# ── Secrets sourced from the External Secrets Operator ───────────────────────

variable "secret_store_name" {
  description = "ClusterSecretStore backing the External Secrets Operator on this platform."
  type        = string
}

variable "enable_keda" {
  description = <<-EOT
    KEDA Postgres scaler on the worker Deployment. Portable, free, and needs no
    broker (Implementation Architecture Decisions §4).
  EOT
  type        = bool
  default     = true
}
