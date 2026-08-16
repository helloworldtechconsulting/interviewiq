# =============================================================================
# platform/oci — Oracle Kubernetes Engine on the Always Free tier
#
# THIN and cloud-specific, emitting the same output contract as every other
# platform module.
#
# WHY OCI IS THE STAGING TARGET. Arch v4.0 §6 makes two points that land
# together. First, "Oracle Always Free could run this entire MVP permanently at
# Rs.0" — 4 Ampere Arm cores and 24 GB RAM, no expiry and no application
# process, with a free OKE control plane. Second, and more important:
#
#   "Run staging on a different cloud from production. Portability that has
#    never been exercised is a hope, not a property."
#
# Staging here and production on GCP proves the cloud-agnostic claim
# continuously, and costs almost nothing to do.
# =============================================================================

terraform {
  required_version = ">= 1.6"
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 5.40"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

resource "oci_containerengine_cluster" "primary" {
  compartment_id     = var.compartment_id
  kubernetes_version = var.kubernetes_version
  name               = "interviewengine-${var.environment}"
  vcn_id             = oci_core_vcn.vcn.id

  # BASIC_CLUSTER is the free control plane. ENHANCED_CLUSTER is billed and buys
  # nothing this workload needs.
  type = "BASIC_CLUSTER"

  endpoint_config {
    is_public_ip_enabled = true
    subnet_id            = oci_core_subnet.api.id
  }

  options {
    service_lb_subnet_ids = [oci_core_subnet.lb.id]
    kubernetes_network_config {
      pods_cidr     = "10.244.0.0/16"
      services_cidr = "10.96.0.0/16"
    }
  }
}

resource "oci_containerengine_node_pool" "primary" {
  cluster_id         = oci_containerengine_cluster.primary.id
  compartment_id     = var.compartment_id
  kubernetes_version = var.kubernetes_version
  name               = "interviewengine-${var.environment}-pool"

  node_config_details {
    size = var.node_count
    placement_configs {
      availability_domain = var.availability_domain
      subnet_id           = oci_core_subnet.nodes.id
    }
  }

  # Ampere Arm — the Always Free allocation. The application runs unchanged on
  # ARM64 (PRD §9).
  node_shape = "VM.Standard.A1.Flex"
  node_shape_config {
    ocpus         = var.node_ocpus
    memory_in_gbs = var.node_memory_gb
  }

  node_source_details {
    image_id    = var.node_image_id
    source_type = "IMAGE"
  }
}

# ── Network ──────────────────────────────────────────────────────────────────
resource "oci_core_vcn" "vcn" {
  compartment_id = var.compartment_id
  cidr_blocks    = ["10.30.0.0/16"]
  display_name   = "interviewengine-${var.environment}"
  dns_label      = "iiq${substr(var.environment, 0, 4)}"
}

resource "oci_core_internet_gateway" "igw" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.vcn.id
  display_name   = "interviewengine-${var.environment}"
}

resource "oci_core_route_table" "public" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.vcn.id
  display_name   = "interviewengine-${var.environment}-public"

  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_internet_gateway.igw.id
  }
}

resource "oci_core_subnet" "nodes" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.vcn.id
  cidr_block     = "10.30.1.0/24"
  display_name   = "interviewengine-${var.environment}-nodes"
  route_table_id = oci_core_route_table.public.id
  dns_label      = "nodes"
}

resource "oci_core_subnet" "lb" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.vcn.id
  cidr_block     = "10.30.2.0/24"
  display_name   = "interviewengine-${var.environment}-lb"
  route_table_id = oci_core_route_table.public.id
  dns_label      = "lb"
}

resource "oci_core_subnet" "api" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.vcn.id
  cidr_block     = "10.30.3.0/28"
  display_name   = "interviewengine-${var.environment}-api"
  route_table_id = oci_core_route_table.public.id
  dns_label      = "k8sapi"
}

# ── PostgreSQL ───────────────────────────────────────────────────────────────
# OCI's managed PostgreSQL is the "thinner managed services" trade-off Arch v4.0
# §6 names when it recommends evaluating rather than dismissing Oracle. For
# staging that is entirely acceptable — and exercising a different managed
# Postgres is part of what makes the portability claim real.
resource "oci_psql_db_system" "postgres" {
  compartment_id = var.compartment_id
  db_version     = "16"
  display_name   = "interviewengine-${var.environment}"
  shape          = var.db_shape

  instance_count              = 1
  instance_ocpu_count         = var.db_ocpus
  instance_memory_size_in_gbs = var.db_memory_gb

  network_details {
    subnet_id = oci_core_subnet.nodes.id
  }

  storage_details {
    system_type = "OCI_OPTIMIZED_STORAGE"
    iops        = 75000
    # Zonal on staging. HA triggers at more than 20 paying customers (PRD §5.2),
    # and staging exists to exercise portability, not durability.
    is_regionally_durable = false
  }

  credentials {
    username = "interviewengine"
    password_details {
      password_type = "PLAIN_TEXT"
      password      = random_password.db.result
    }
  }
}

resource "random_password" "db" {
  length  = 32
  special = false
}
