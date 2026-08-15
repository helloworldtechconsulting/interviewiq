# =============================================================================
# PRODUCTION — GCP
#
# Arch v4.0 §4: "Changing cloud is changing one variable and re-applying."
# That variable is `platform`, below. Everything past the platform module is
# identical to staging, which runs on Oracle.
#
# GKE because one zonal control plane is free. Arch v4.0 §6: "cloud-agnostic
# effectively means 'not EKS'."
# =============================================================================

terraform {
  required_version = ">= 1.6"

  backend "gcs" {
    bucket = "interviewiq-tfstate-production"
    prefix = "production"
  }

  required_providers {
    google     = { source = "hashicorp/google", version = "~> 5.30" }
    kubernetes = { source = "hashicorp/kubernetes", version = "~> 2.30" }
    helm       = { source = "hashicorp/helm", version = "~> 2.13" }
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 4.35" }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# ── The one cloud-specific layer ─────────────────────────────────────────────
module "platform" {
  source = "../../modules/platform/gcp"

  environment = "production"
  project_id  = var.project_id
  region      = var.region
  zone        = var.zone

  node_count   = 2
  machine_type = "t2a-standard-2" # ARM64
  db_tier      = "db-custom-1-3840"

  # Cloudflare R2 on every cloud — zero egress, and recording playback is the
  # only meaningful egress this product generates.
  object_storage_endpoint   = var.r2_endpoint
  object_storage_bucket     = var.r2_bucket
  object_storage_access_key = var.r2_access_key
  object_storage_secret_key = var.r2_secret_key
}

# Providers configured from the platform module's output contract. This is the
# seam: everything below consumes `module.platform.*` and knows nothing else.
provider "kubernetes" {
  host                   = module.platform.kubeconfig.host
  cluster_ca_certificate = base64decode(module.platform.kubeconfig.cluster_ca_certificate)
  token                  = data.google_client_config.default.access_token
}

provider "helm" {
  kubernetes {
    host                   = module.platform.kubeconfig.host
    cluster_ca_certificate = base64decode(module.platform.kubeconfig.cluster_ca_certificate)
    token                  = data.google_client_config.default.access_token
  }
}

data "google_client_config" "default" {}

# ── The cloud-agnostic layer ─────────────────────────────────────────────────
module "workload" {
  source = "../../modules/workload"

  environment = "production"
  image       = var.image
  api_host    = "api.${var.zone_name}"

  frontend_origins = ["https://app.${var.zone_name}"]

  postgres_url      = module.platform.postgres_url
  postgres_username = module.platform.postgres_username
  postgres_password = module.platform.postgres_password

  object_storage_endpoint   = module.platform.object_storage_endpoint
  object_storage_bucket     = module.platform.object_storage_bucket
  object_storage_region     = module.platform.object_storage_region
  object_storage_access_key = module.platform.object_storage_access_key
  object_storage_secret_key = module.platform.object_storage_secret_key

  secret_store_name = module.platform.secret_store_name

  web_min_replicas    = 2
  web_max_replicas    = 6
  worker_min_replicas = 1
  worker_max_replicas = 4
  hikari_pool_size    = 10
  bucket_capacity     = 25
}

# ── The edge — identical on every cloud ──────────────────────────────────────
module "edge" {
  source = "../../modules/edge"

  zone_id             = var.cloudflare_zone_id
  zone_name           = var.zone_name
  ingress_ip          = var.ingress_ip
  spa_origin_hostname = var.spa_origin_hostname
}
