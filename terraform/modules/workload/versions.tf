# =============================================================================
# workload — providers
#
# kubernetes_* and helm_* resources ONLY. Arch v4.0 §4: this module is 100%
# cloud-agnostic and "never learns which cloud it is on". A provider block for
# any cloud SDK appearing here would break that property.
# =============================================================================

terraform {
  required_version = ">= 1.6"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.30"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.13"
    }
  }
}
