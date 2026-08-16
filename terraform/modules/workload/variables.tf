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
  default = "interviewengine"
}

variable "api_host" {
  description = "Public API hostname, e.g. api.interviewengine.ai."
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
    HikariCP connections per WEB pod (Arch v4.0 §5.4).

    Web pods serve request traffic, so their pool is sized for concurrency
    rather than throughput.
  EOT
  type        = number
  default     = 10
}

variable "worker_hikari_pool_size" {
  description = <<-EOT
    HikariCP connections per WORKER pod.

    Smaller than the web pool on purpose. Workers run a fixed number of polling
    loops claiming batches with FOR UPDATE SKIP LOCKED; concurrency is bounded
    by the number of pollers, not by inbound requests, so a large pool would
    reserve connections that are never used while still counting against the
    database's ceiling.

    This existing as a separate variable is the point. It previously did not,
    so worker pods silently fell back to the application.yml default of 10 and
    peak usage was 6x10 + 4x10 = 100 against a ~112 connection limit — while
    the comment here claimed 84. Twelve spare connections is not enough to
    survive a rolling deploy, where old and new pods hold pools at the same
    time.
  EOT
  type        = number
  default     = 6
}

variable "database_max_connections" {
  description = <<-EOT
    Connections the managed PostgreSQL instance allows. Used only to check that
    a fully scaled-out deployment cannot exhaust it — see the check block in
    deployment_web.tf.

    Verify against the real instance with SHOW max_connections; the default
    here is the db.t4g.micro-class figure Arch v4.0 §5.4 quotes.
  EOT
  type        = number
  default     = 112
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

# ── Observability (Arch v4.0 §3, PRD §8) ────────────────────────────────────

variable "monitoring_enabled" {
  description = <<-EOT
    Provision Prometheus, Grafana and Alertmanager in this environment.

    Toggleable because the stack is not free: it wants roughly 1.5 GB of
    requests, which is material on a two-node staging cluster. Production
    should always have it on.
  EOT
  type        = bool
  default     = true
}

variable "kube_prometheus_stack_version" {
  description = "Pinned chart version — an unpinned Helm chart makes every apply a different deployment."
  type        = string
  default     = "65.1.1"
}

variable "metrics_retention" {
  description = "How long Prometheus keeps samples. Local storage, so this is bounded by disk."
  type        = string
  default     = "15d"
}

variable "grafana_admin_password" {
  description = "Grafana admin password. Supplied from the environment's secret store, never defaulted."
  type        = string
  sensitive   = true
}
