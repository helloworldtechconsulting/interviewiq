terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.10.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 5.10.0"
    }
  }

  backend "gcs" {
    bucket = "interviewiq-terraform-state"
    prefix = "terraform/state"
  }
}

provider "google" {
  project = var.gcp_project_id
  region  = var.gcp_region
}

provider "google-beta" {
  project = var.gcp_project_id
  region  = var.gcp_region
}

# Enable required APIs
resource "google_project_service" "required_apis" {
  for_each = toset([
    "compute.googleapis.com",
    "run.googleapis.com",
    "sqladmin.googleapis.com",
    "storage.googleapis.com",
    "secretmanager.googleapis.com",
    "servicenetworking.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "monitoring.googleapis.com",
    "logging.googleapis.com",
    "artifactregistry.googleapis.com",
    "containerregistry.googleapis.com",
  ])

  service            = each.value
  disable_on_destroy = false
}

# Networking Module
module "networking" {
  source = "./modules/networking"

  gcp_project_id = var.gcp_project_id
  gcp_region     = var.gcp_region
  environment    = var.environment
  vpc_cidr       = var.vpc_cidr
  subnet_cidr    = var.subnet_cidr

  depends_on = [google_project_service.required_apis]
}

# Cloud SQL Module
module "cloud_sql" {
  source = "./modules/cloud-sql"

  gcp_project_id             = var.gcp_project_id
  gcp_region                 = var.gcp_region
  environment                = var.environment
  database_instance_name     = var.database_instance_name
  database_name              = var.database_name
  database_user              = var.database_user
  instance_tier              = var.instance_tier
  availability_type          = var.availability_type
  backup_retention_days      = var.backup_retention_days
  private_network_id         = module.networking.vpc_id

  depends_on = [
    google_project_service.required_apis,
    module.networking
  ]
}

# GCS Module
module "gcs" {
  source = "./modules/gcs"

  gcp_project_id  = var.gcp_project_id
  gcp_region      = var.gcp_region
  environment     = var.environment
  data_bucket     = var.data_bucket_name
  frontend_bucket = var.frontend_bucket_name
  project_id      = var.gcp_project_id

  depends_on = [google_project_service.required_apis]
}

# Secrets Module
module "secrets" {
  source = "./modules/secrets"

  gcp_project_id        = var.gcp_project_id
  environment           = var.environment
  db_password           = var.db_password
  openai_api_key        = var.openai_api_key
  razorpay_key_id       = var.razorpay_key_id
  razorpay_key_secret   = var.razorpay_key_secret
  jwt_secret            = var.jwt_secret
  razorpay_webhook_secret = var.razorpay_webhook_secret

  depends_on = [google_project_service.required_apis]
}

# Cloud Run Module
module "cloud_run" {
  source = "./modules/cloud-run"

  gcp_project_id           = var.gcp_project_id
  gcp_region               = var.gcp_region
  environment              = var.environment
  service_name             = var.cloud_run_service_name
  image_url                = var.backend_image_url
  container_port           = var.container_port
  cpu                      = var.cloud_run_cpu
  memory                   = var.cloud_run_memory
  min_instances            = var.cloud_run_min_instances
  max_instances            = var.cloud_run_max_instances
  vpc_connector_id         = module.networking.vpc_connector_id
  artifact_registry_repository = var.artifact_registry_repository

  # Secrets
  db_password_secret_id           = module.secrets.db_password_secret_id
  openai_api_key_secret_id        = module.secrets.openai_api_key_secret_id
  razorpay_key_id_secret_id       = module.secrets.razorpay_key_id_secret_id
  razorpay_key_secret_secret_id   = module.secrets.razorpay_key_secret_secret_id
  jwt_secret_id                   = module.secrets.jwt_secret_id
  razorpay_webhook_secret_id      = module.secrets.razorpay_webhook_secret_id

  # Database
  cloud_sql_instance_connection = module.cloud_sql.instance_connection_name
  database_name                = var.database_name
  database_user                = var.database_user

  # Storage
  gcs_data_bucket = module.gcs.data_bucket_name

  depends_on = [
    google_project_service.required_apis,
    module.secrets,
    module.networking,
    module.cloud_sql,
    module.gcs
  ]
}

# Monitoring Module
module "monitoring" {
  source = "./modules/monitoring"

  gcp_project_id              = var.gcp_project_id
  environment                 = var.environment
  cloud_run_service_url       = module.cloud_run.service_url
  notification_email_address  = var.notification_email_address
  alert_cpu_threshold         = var.alert_cpu_threshold
  alert_5xx_error_threshold   = var.alert_5xx_error_threshold
  alert_sql_connections_threshold = var.alert_sql_connections_threshold

  depends_on = [
    google_project_service.required_apis,
    module.cloud_run,
    module.cloud_sql
  ]
}

# Artifact Registry Repository
resource "google_artifact_registry_repository" "docker_repo" {
  location      = var.gcp_region
  repository_id = var.artifact_registry_repository
  description   = "Docker repository for InterviewIQ ${var.environment}"
  format        = "DOCKER"

  depends_on = [google_project_service.required_apis]
}
