# =============================================================================
# STAGING — GCP
#
# Both environments now run on GKE. Staging is the same Kubernetes workload as
# production, on the same cloud, sized down.
#
# ── A documented decision this reverses ──────────────────────────────────────
#
# Architecture v4.0 §6 and decision #14 both say to run staging on a DIFFERENT
# cloud from production:
#
#   "Run staging on a different cloud from production. Portability that has
#    never been exercised is a hope, not a property."
#
# That reasoning still holds, and moving staging to GCP gives it up: nothing
# routinely applies the workload module against a non-GCP platform any more, so
# a GCP-specific assumption can now reach production without staging catching
# it. The OCI platform module is kept, complete and current, so the property
# can be re-established — see `platform_target` below — but a module that is
# never applied does decay, and this one now will unless it is exercised
# deliberately.
#
# The mitigation is that the seam is still real and still enforced: everything
# below the platform module consumes only `local.platform.*`, the output
# contract every platform module implements. Switching staging back to Oracle
# is changing `platform_target` and re-applying, which is what Arch §4 promises
# and what the previous hardcoded `source = ".../oci"` did not actually deliver.
#
# ── How the switch works ─────────────────────────────────────────────────────
#
# A module's `source` cannot be a variable, so both platform modules are
# declared and `count` selects one. The unselected module produces no
# resources, and `local.platform` resolves whichever is live.
# =============================================================================

terraform {
  required_version = ">= 1.6"

  backend "gcs" {
    bucket = "interviewengine-tfstate-staging"
    prefix = "staging"
  }

  required_providers {
    google     = { source = "hashicorp/google", version = "~> 5.30" }
    oci        = { source = "oracle/oci", version = "~> 5.40" }
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

module "platform_gcp" {
  count  = var.platform_target == "gcp" ? 1 : 0
  source = "../../modules/platform/gcp"

  environment = "staging"
  project_id  = var.project_id
  region      = var.region
  zone        = var.zone

  # Two small ARM nodes. Staging carries no real load; the node count exists so
  # that pod scheduling and topology spread behave as they do in production
  # rather than collapsing onto one node.
  node_count = 2
  # standard-2 rather than standard-1: the monitoring stack below wants
  # roughly 1.5 GB of requests, and running it in staging is the only way
  # the alert rules get exercised before production depends on them.
  machine_type = "t2a-standard-2"
  db_tier      = "db-f1-micro"

  object_storage_endpoint   = var.r2_endpoint
  object_storage_bucket     = var.r2_bucket
  object_storage_access_key = var.r2_access_key
  object_storage_secret_key = var.r2_secret_key
}

module "platform_oci" {
  count  = var.platform_target == "oci" ? 1 : 0
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

# ── The seam ─────────────────────────────────────────────────────────────────
# Everything past this point reads local.platform and nothing else. one()
# returns null for the module that was not selected, so coalesce picks the live
# one without either branch being evaluated when absent.
locals {
  platform = {
    kubeconfig = coalesce(
      one(module.platform_gcp[*].kubeconfig),
      one(module.platform_oci[*].kubeconfig),
    )
    postgres_url = coalesce(
      one(module.platform_gcp[*].postgres_url),
      one(module.platform_oci[*].postgres_url),
    )
    postgres_username = coalesce(
      one(module.platform_gcp[*].postgres_username),
      one(module.platform_oci[*].postgres_username),
    )
    postgres_password = coalesce(
      one(module.platform_gcp[*].postgres_password),
      one(module.platform_oci[*].postgres_password),
    )
    object_storage_endpoint = coalesce(
      one(module.platform_gcp[*].object_storage_endpoint),
      one(module.platform_oci[*].object_storage_endpoint),
    )
    object_storage_bucket = coalesce(
      one(module.platform_gcp[*].object_storage_bucket),
      one(module.platform_oci[*].object_storage_bucket),
    )
    object_storage_region = coalesce(
      one(module.platform_gcp[*].object_storage_region),
      one(module.platform_oci[*].object_storage_region),
    )
    object_storage_access_key = coalesce(
      one(module.platform_gcp[*].object_storage_access_key),
      one(module.platform_oci[*].object_storage_access_key),
    )
    object_storage_secret_key = coalesce(
      one(module.platform_gcp[*].object_storage_secret_key),
      one(module.platform_oci[*].object_storage_secret_key),
    )
    secret_store_name = coalesce(
      one(module.platform_gcp[*].secret_store_name),
      one(module.platform_oci[*].secret_store_name),
    )
  }
}

data "google_client_config" "default" {}

# Cluster authentication is the one thing that cannot come through the output
# contract: GKE takes a bearer token from the google provider, while OKE needs
# an exec plugin shelling out to the OCI CLI. A provider block cannot be made
# conditional, so this one is written for the GKE path.
#
# Switching platform_target to "oci" therefore also means restoring the exec
# block that was here before — kept in git history at this path. That is the
# honest cost of the switch and the reason it is not purely a one-variable
# change for the provider layer, even though it is for everything below.
provider "kubernetes" {
  host                   = local.platform.kubeconfig.host
  cluster_ca_certificate = base64decode(local.platform.kubeconfig.cluster_ca_certificate)
  token                  = data.google_client_config.default.access_token
}

provider "helm" {
  kubernetes {
    host                   = local.platform.kubeconfig.host
    cluster_ca_certificate = base64decode(local.platform.kubeconfig.cluster_ca_certificate)
    token                  = data.google_client_config.default.access_token
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

  postgres_url      = local.platform.postgres_url
  postgres_username = local.platform.postgres_username
  postgres_password = local.platform.postgres_password

  object_storage_endpoint   = local.platform.object_storage_endpoint
  object_storage_bucket     = local.platform.object_storage_bucket
  object_storage_region     = local.platform.object_storage_region
  object_storage_access_key = local.platform.object_storage_access_key
  object_storage_secret_key = local.platform.object_storage_secret_key

  secret_store_name = local.platform.secret_store_name

  # Staging carries no real load, but the web floor stays at 2 so that pod
  # eviction during a rollout is actually exercised here rather than discovered
  # in production.
  web_min_replicas    = 2
  web_max_replicas    = 3
  worker_min_replicas = 1
  worker_max_replicas = 2

  # db-f1-micro allows far fewer connections than the production tier, so the
  # pools are sized down to match. Left at production's values, the connection
  # budget check would fail here — correctly, because staging's database is
  # smaller.
  hikari_pool_size         = 5
  worker_hikari_pool_size  = 3
  database_max_connections = 50

  bucket_capacity = 10

  # Enabled here too, deliberately. An alert rule that has only ever been
  # applied in production is an alert rule nobody has seen fire.
  monitoring_enabled     = true
  grafana_admin_password = var.grafana_admin_password

  # Staging generates no meaningful history and the disk is small.
  metrics_retention = "3d"
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
