# =============================================================================
# STAGING variables
#
# Split into three groups: the platform selector, the inputs the selected
# platform needs, and the inputs every platform needs. The cloud-specific
# groups are optional — a GCP staging never supplies the OCI values and vice
# versa, so they default to empty rather than being required.
# =============================================================================

variable "platform_target" {
  description = <<-EOT
    Which cloud staging runs on: "gcp" or "oci".

    Arch v4.0 §4 promises that changing cloud is changing one variable. This is
    that variable for the resource layer. The kubernetes/helm provider blocks
    in main.tf are the one exception — they cannot be made conditional and are
    currently written for GKE's bearer-token auth.
  EOT
  type        = string
  default     = "gcp"

  validation {
    condition     = contains(["gcp", "oci"], var.platform_target)
    error_message = "platform_target must be \"gcp\" or \"oci\"."
  }
}

# ── Common to every platform ─────────────────────────────────────────────────

variable "region" {
  type = string
  # Candidate data at rest in India (PRD §8). Note this is a GCP region name;
  # switching platform_target to "oci" means switching this to "ap-mumbai-1".
  default = "asia-south1"
}

variable "zone_name" { type = string }

variable "image" {
  description = "GHCR image — the SAME image production runs."
  type        = string
}

variable "cloudflare_zone_id" { type = string }
variable "ingress_ip" { type = string }
variable "spa_origin_hostname" { type = string }

variable "r2_endpoint" { type = string }
variable "r2_bucket" { type = string }

variable "r2_access_key" {
  type      = string
  sensitive = true
}

variable "r2_secret_key" {
  type      = string
  sensitive = true
}

# ── Required when platform_target = "gcp" ────────────────────────────────────

variable "project_id" {
  description = "GCP project. Required when platform_target is \"gcp\"."
  type        = string
  default     = ""
}

variable "zone" {
  description = <<-EOT
    GCP zone for the zonal GKE cluster. Required when platform_target is "gcp".

    Zonal rather than regional because a single zonal control plane is free on
    GKE (Arch v4.0 §6), which is the whole cost argument for this cloud.
  EOT
  type        = string
  default     = "asia-south1-a"
}

# ── Required when platform_target = "oci" ────────────────────────────────────

variable "compartment_id" {
  description = "OCI compartment. Required when platform_target is \"oci\"."
  type        = string
  default     = ""
}

variable "availability_domain" {
  description = "OCI availability domain. Required when platform_target is \"oci\"."
  type        = string
  default     = ""
}

variable "node_image_id" {
  description = "Oracle Linux ARM64 image OCID for the region. Required when platform_target is \"oci\"."
  type        = string
  default     = ""
}

variable "grafana_admin_password" {
  description = "Grafana admin login. Sourced from the deployment secret store, never committed."
  type        = string
  sensitive   = true
}
