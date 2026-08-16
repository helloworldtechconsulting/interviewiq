variable "environment" { type = string }
variable "project_id" { type = string }

variable "region" {
  type    = string
  default = "asia-south1" # Mumbai — candidate data at rest in India (PRD §8)
}

variable "zone" {
  description = "A ZONAL cluster keeps the GKE control plane free (Arch v4.0 §6)."
  type        = string
  default     = "asia-south1-a"
}

variable "node_count" {
  type    = number
  default = 2 # min 2 pods across 2 nodes (PRD §8, availability)
}

variable "machine_type" {
  description = "ARM64 (t2a) where available — the application runs unchanged and instances are cheaper."
  type        = string
  default     = "t2a-standard-1"
}

variable "db_tier" {
  type    = string
  default = "db-f1-micro"
}

variable "db_max_connections" {
  description = <<-EOT
    Must exceed 6 web x 10 + 4 worker x 6 = 84 (Arch v4.0 §5.4). Verify against
    the instance's real ceiling with SHOW max_connections after provisioning.
  EOT
  type        = number
  default     = 112
}

# ── Object storage — Cloudflare R2 on every cloud (Arch v4.0 §3) ─────────────
# Passed through rather than provisioned, so the store does not move when the
# compute does. R2's zero egress is material: recording playback is the only
# meaningful egress this product generates.
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
