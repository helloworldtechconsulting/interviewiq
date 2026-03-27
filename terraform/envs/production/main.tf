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
    prefix = "terraform/production"
  }
}

module "interviewiq" {
  source = "../../"

  gcp_project_id  = var.gcp_project_id
  gcp_region      = var.gcp_region
  environment     = "production"

  vpc_cidr    = var.vpc_cidr
  subnet_cidr = var.subnet_cidr

  database_instance_name = var.database_instance_name
  database_name          = var.database_name
  database_user          = var.database_user
  db_password            = var.db_password
  instance_tier          = "db-custom-1-3840"
  availability_type      = "REGIONAL"
  backup_retention_days  = 30

  data_bucket_name     = var.data_bucket_name
  frontend_bucket_name = var.frontend_bucket_name

  openai_api_key            = var.openai_api_key
  razorpay_key_id           = var.razorpay_key_id
  razorpay_key_secret       = var.razorpay_key_secret
  razorpay_webhook_secret   = var.razorpay_webhook_secret
  jwt_secret                = var.jwt_secret

  cloud_run_service_name = var.cloud_run_service_name
  backend_image_url      = var.backend_image_url
  container_port         = 8080
  cloud_run_cpu          = "2"
  cloud_run_memory       = "2Gi"
  cloud_run_min_instances = 2
  cloud_run_max_instances = 20

  artifact_registry_repository = var.artifact_registry_repository

  notification_email_address = var.notification_email_address
  alert_cpu_threshold        = 70
  alert_5xx_error_threshold  = 0.5
  alert_sql_connections_threshold = 70
}
