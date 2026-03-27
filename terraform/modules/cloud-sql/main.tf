locals {
  database_version = "POSTGRES_15"
}

# Cloud SQL Instance
resource "google_sql_database_instance" "postgres" {
  name             = var.database_instance_name
  database_version = local.database_version
  region           = var.gcp_region

  settings {
    tier              = var.instance_tier
    availability_type = var.availability_type
    disk_size         = 20
    disk_type         = "PD_SSD"
    disk_autoresize   = true

    # Backup Configuration
    backup_configuration {
      enabled                        = true
      start_time                     = "03:00"
      backup_retention_settings {
        retained_backups = var.backup_retention_days
        retention_unit   = "COUNT"
      }
    }

    # Maintenance Window
    maintenance_window {
      day          = 6  # Saturday
      hour         = 2
      update_track = "stable"
    }

    # IP Configuration - Private IP only
    ip_configuration {
      ipv4_enabled    = false
      private_network = var.private_network_id
      require_ssl     = true

      # Authorized networks - not needed with private IP
      authorized_networks = []
    }

    # Location preference
    location_preference {
      zone = "${var.gcp_region}-a"
    }

    # Database flags
    database_flags {
      name  = "max_connections"
      value = "100"
    }

    database_flags {
      name  = "shared_buffers"
      value = "262144"
    }

    database_flags {
      name  = "log_statement"
      value = "all"
    }

    # Insights configuration
    insights_config {
      query_insights_enabled  = true
      query_string_length     = 1024
      record_application_tags = true
      record_client_address   = true
    }
  }

  deletion_protection = false
  depends_on         = []
}

# Database
resource "google_sql_database" "database" {
  name     = var.database_name
  instance = google_sql_database_instance.postgres.name
  charset  = "UTF8"
}

# Database User
resource "google_sql_user" "db_user" {
  name     = var.database_user
  instance = google_sql_database_instance.postgres.name
  password = var.database_password

  type = "BUILT_IN"
}

# Random suffix for replica name to avoid conflicts
resource "random_id" "db_suffix" {
  byte_length = 4
}

# Replica for automated failover (only for production)
resource "google_sql_database_instance" "postgres_replica" {
  count                = var.availability_type == "REGIONAL" ? 1 : 0
  name                 = "${var.database_instance_name}-replica-${random_id.db_suffix.hex}"
  database_version     = local.database_version
  region               = var.gcp_region
  master_instance_name = google_sql_database_instance.postgres.name

  replica_configuration {
    kind             = "REPLICA"
    mysql_replica_configuration {}
  }

  settings {
    tier              = var.instance_tier
    availability_type = "ZONAL"
    disk_size         = 20
    disk_type         = "PD_SSD"
    disk_autoresize   = true

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.private_network_id
      require_ssl     = true
    }

    location_preference {
      zone = "${var.gcp_region}-b"
    }
  }

  depends_on = [google_sql_database_instance.postgres]
}
