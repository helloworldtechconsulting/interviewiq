# =============================================================================
# platform/gcp — GKE, Cloud SQL, and R2 for object storage
#
# THIN and cloud-specific. Its only job is to emit the fixed output contract in
# outputs.tf, which EVERY platform module emits identically (Arch v4.0 §4).
#
# GKE is the recommended production target for one reason above the others: one
# zonal cluster's control plane is FREE. Arch v4.0 §6 puts it plainly —
# "cloud-agnostic effectively means 'not EKS'", because every other managed
# Kubernetes has a free control plane and EKS charges ~$73/month.
# =============================================================================

terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.30"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

# ── Kubernetes: one ZONAL cluster (free control plane) ───────────────────────
resource "google_container_cluster" "primary" {
  name     = "interviewengine-${var.environment}"
  location = var.zone # zonal, not regional — regional control planes are billed

  # Terraform cannot create a cluster with no node pool, so the default is
  # created and immediately removed in favour of the managed pool below.
  remove_default_node_pool = true
  initial_node_count       = 1

  release_channel {
    channel = "REGULAR"
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  # Private nodes with a public control-plane endpoint: nodes are unreachable
  # from the internet, and Cloudflare fronts the ingress regardless.
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
    master_ipv4_cidr_block  = "172.16.0.0/28"
  }

  ip_allocation_policy {}

  network    = google_compute_network.vpc.id
  subnetwork = google_compute_subnetwork.subnet.id

  deletion_protection = var.environment == "production"
}

resource "google_container_node_pool" "primary" {
  name       = "interviewengine-${var.environment}-pool"
  cluster    = google_container_cluster.primary.id
  node_count = var.node_count

  node_config {
    # ARM64 where the cloud offers it (PRD §9). The application runs unchanged
    # on ARM and the instances are cheaper.
    machine_type = var.machine_type
    disk_size_gb = 50
    disk_type    = "pd-standard"

    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }
}

# ── Network ──────────────────────────────────────────────────────────────────
resource "google_compute_network" "vpc" {
  name                    = "interviewengine-${var.environment}"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name          = "interviewengine-${var.environment}"
  ip_cidr_range = "10.20.0.0/20"
  region        = var.region
  network       = google_compute_network.vpc.id

  private_ip_google_access = true
}

# Private nodes need NAT for egress — LLM providers, Razorpay, SMTP.
resource "google_compute_router" "router" {
  name    = "interviewengine-${var.environment}"
  region  = var.region
  network = google_compute_network.vpc.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "interviewengine-${var.environment}"
  router                             = google_compute_router.router.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

# ── Managed PostgreSQL 16 ────────────────────────────────────────────────────
# Managed rather than containerised: managed backups and PITR are worth the
# ~$15/month (PRD §6.2). CloudNativePG in-cluster remains the documented
# fallback if a cloud's managed offering is poor.
resource "google_sql_database_instance" "postgres" {
  name             = "interviewengine-${var.environment}-${random_id.db_suffix.hex}"
  database_version = "POSTGRES_16"
  region           = var.region

  settings {
    tier              = var.db_tier
    availability_type = "ZONAL" # HA triggers at >20 paying customers (PRD §5.2)
    disk_size         = 20
    disk_autoresize   = true

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "18:30" # 00:00 IST — outside Indian business hours
      backup_retention_settings {
        retained_backups = 7
      }
    }

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.vpc.id
      ssl_mode        = "ENCRYPTED_ONLY"
    }

    database_flags {
      name  = "max_connections"
      value = tostring(var.db_max_connections)
    }
  }

  deletion_protection = var.environment == "production"

  depends_on = [google_service_networking_connection.private_vpc]
}

resource "google_compute_global_address" "private_ip" {
  name          = "interviewengine-${var.environment}-db"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "private_vpc" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip.name]
}

resource "google_sql_database" "app" {
  name     = "interviewengine"
  instance = google_sql_database_instance.postgres.name
}

resource "google_sql_user" "app" {
  name     = "interviewengine"
  instance = google_sql_database_instance.postgres.name
  password = random_password.db.result
}

resource "random_password" "db" {
  length  = 32
  special = false # avoids URL-encoding hazards in the JDBC and libpq strings
}

resource "random_id" "db_suffix" {
  byte_length = 4
}
