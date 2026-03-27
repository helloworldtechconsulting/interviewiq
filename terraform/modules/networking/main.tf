locals {
  network_name              = "interviewiq-${var.environment}-vpc"
  subnet_name               = "interviewiq-${var.environment}-subnet"
  vpc_connector_name        = "interviewiq-${var.environment}-connector"
  router_name               = "interviewiq-${var.environment}-router"
  nat_name                  = "interviewiq-${var.environment}-nat"
  cloud_sql_vpc_peering     = "private-ip-address"
}

# Create VPC Network
resource "google_compute_network" "vpc" {
  name                    = local.network_name
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
}

# Create Subnet
resource "google_compute_subnetwork" "subnet" {
  name          = local.subnet_name
  ip_cidr_range = var.subnet_cidr
  region        = var.gcp_region
  network       = google_compute_network.vpc.id

  private_ip_google_access = true
}

# Reserve IP range for Private Service Access (Cloud SQL)
resource "google_compute_global_address" "private_ip_address" {
  name          = "interviewiq-${var.environment}-private-ip"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

# Create Private Service Connection (for Cloud SQL)
resource "google_service_networking_connection" "private_vpc_connection" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip_address.name]
}

# Cloud Router for Cloud NAT
resource "google_compute_router" "router" {
  name    = local.router_name
  region  = var.gcp_region
  network = google_compute_network.vpc.id

  bgp {
    asn = 64514
  }
}

# Cloud NAT for egress traffic
resource "google_compute_router_nat" "nat" {
  name                               = local.nat_name
  router                             = google_compute_router.router.name
  region                             = google_compute_router.router.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# Firewall rule: Allow ingress from load balancer to Cloud Run VPC Connector
resource "google_compute_firewall" "allow_lb_to_connector" {
  name    = "interviewiq-${var.environment}-allow-lb"
  network = google_compute_network.vpc.name

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_ranges = ["130.211.0.0/22", "35.191.0.0/16"] # GCP Load Balancer ranges
}

# Firewall rule: Allow internal communication
resource "google_compute_firewall" "allow_internal" {
  name    = "interviewiq-${var.environment}-allow-internal"
  network = google_compute_network.vpc.name

  allow {
    protocol = "tcp"
    ports    = ["5432"] # PostgreSQL
  }
  allow {
    protocol = "icmp"
  }

  source_ranges = [var.vpc_cidr]
}

# Firewall rule: Deny all ingress except allowed
resource "google_compute_firewall" "deny_all_ingress" {
  name      = "interviewiq-${var.environment}-deny-all"
  network   = google_compute_network.vpc.name
  priority  = 1000
  direction = "INGRESS"

  deny {
    protocol = "all"
  }

  source_ranges = ["0.0.0.0/0"]
}

# VPC Connector for Cloud Run to access Cloud SQL
resource "google_vpc_access_connector" "connector" {
  name          = local.vpc_connector_name
  region        = var.gcp_region
  ip_cidr_range = "10.8.0.0/28"
  network       = google_compute_network.vpc.name

  depends_on = [google_service_networking_connection.private_vpc_connection]
}
