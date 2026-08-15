# =============================================================================
# STAGING — Oracle Cloud (deliberately NOT the production cloud)
#
# Arch v4.0 §6 and PRD §8 give the same instruction, and it is the reason this
# file exists at all:
#
#   "Run staging on a different cloud from production. Portability that has
#    never been exercised is a hope, not a property — and staging is small
#    enough to be nearly free."
#
# So staging runs on Oracle Always Free while production runs on GKE. The
# platform module differs; the workload and edge modules below are IDENTICAL to
# production's, character for character. If a change breaks portability, it
# breaks staging first — which is exactly when we want to find out.
#
# Cost: essentially zero. 4 Ampere Arm cores and 24 GB RAM on the Always Free
# tier, with a free OKE control plane, no expiry and no application process.
# =============================================================================

terraform {
  required_version = ">= 1.6"

  backend "s3" {
    # OCI Object Storage speaks the S3 API, so the standard S3 backend works
    # against it with an endpoint override — the same portability property the
    # application relies on for its own storage.
    bucket                      = "interviewiq-tfstate-staging"
    key                         = "staging/terraform.tfstate"
    region                      = "ap-mumbai-1"
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    use_path_style              = true
  }

  required_providers {
    oci        = { source = "oracle/oci", version = "~> 5.40" }
    kubernetes = { source = "hashicorp/kubernetes", version = "~> 2.30" }
    helm       = { source = "hashicorp/helm", version = "~> 2.13" }
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 4.35" }
  }
}

provider "oci" {
  region = var.region
}

# ── The one cloud-specific layer — the ONLY file that differs from production ─
module "platform" {
  source = "../../modules/platform/oci"

  environment         = "staging"
  compartment_id      = var.compartment_id
  availability_domain = var.availability_domain
  node_image_id       = var.node_image_id

  node_count     = 2
  node_ocpus     = 2
  node_memory_gb = 12 # 2 nodes x (2 OCPU, 12 GB) = exactly the Always Free allocation

  object_storage_endpoint   = var.r2_endpoint
  object_storage_bucket     = var.r2_bucket
  object_storage_access_key = var.r2_access_key
  object_storage_secret_key = var.r2_secret_key
}

data "oci_containerengine_cluster_kube_config" "kubeconfig" {
  cluster_id = module.platform.cluster_id
}

locals {
  kubeconfig = yamldecode(data.oci_containerengine_cluster_kube_config.kubeconfig.content)
}

provider "kubernetes" {
  host                   = local.kubeconfig.clusters[0].cluster.server
  cluster_ca_certificate = base64decode(local.kubeconfig.clusters[0].cluster["certificate-authority-data"])
  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "oci"
    args        = local.kubeconfig.users[0].user.exec.args
  }
}

provider "helm" {
  kubernetes {
    host                   = local.kubeconfig.clusters[0].cluster.server
    cluster_ca_certificate = base64decode(local.kubeconfig.clusters[0].cluster["certificate-authority-data"])
    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "oci"
      args        = local.kubeconfig.users[0].user.exec.args
    }
  }
}

# ── The cloud-agnostic layer — IDENTICAL to production ───────────────────────
# Smaller replica counts, same module, same inputs, same output contract. If
# this block ever needs to differ from production's by anything other than
# sizing, the workload module has stopped being cloud-agnostic.
module "workload" {
  source = "../../modules/workload"

  environment = "staging"
  image       = var.image
  api_host    = "api-staging.${var.zone_name}"

  frontend_origins = ["https://app-staging.${var.zone_name}"]

  postgres_url      = module.platform.postgres_url
  postgres_username = module.platform.postgres_username
  postgres_password = module.platform.postgres_password

  object_storage_endpoint   = module.platform.object_storage_endpoint
  object_storage_bucket     = module.platform.object_storage_bucket
  object_storage_region     = module.platform.object_storage_region
  object_storage_access_key = module.platform.object_storage_access_key
  object_storage_secret_key = module.platform.object_storage_secret_key

  secret_store_name = module.platform.secret_store_name

  # Staging carries no real load, but the web floor stays at 2 so that pod
  # eviction during a rollout is actually exercised here rather than discovered
  # in production.
  web_min_replicas    = 2
  web_max_replicas    = 3
  worker_min_replicas = 1
  worker_max_replicas = 2
  hikari_pool_size    = 10
  bucket_capacity     = 10
}

# ── The edge — identical on every cloud ──────────────────────────────────────
module "edge" {
  source = "../../modules/edge"

  zone_id             = var.cloudflare_zone_id
  zone_name           = var.zone_name
  api_subdomain       = "api-staging"
  app_subdomain       = "app-staging"
  ingress_ip          = var.ingress_ip
  spa_origin_hostname = var.spa_origin_hostname
}
