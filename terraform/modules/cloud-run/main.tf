locals {
  service_account_name = "interviewiq-${var.environment}-sa"
}

# Service Account for Cloud Run
resource "google_service_account" "cloud_run" {
  account_id   = local.service_account_name
  display_name = "InterviewIQ ${var.environment} Cloud Run Service Account"
  description  = "Service account for Cloud Run backend"
}

# IAM Role: Cloud SQL Client
resource "google_project_iam_member" "cloud_run_sql_client" {
  project = var.gcp_project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

# IAM Role: Secret Accessor
resource "google_project_iam_member" "cloud_run_secret_accessor" {
  project = var.gcp_project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

# IAM Role: Storage Object Admin (for data bucket)
resource "google_project_iam_member" "cloud_run_storage_admin" {
  project = var.gcp_project_id
  role    = "roles/storage.objectAdmin"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

# IAM Role: Logs Writer
resource "google_project_iam_member" "cloud_run_logs_writer" {
  project = var.gcp_project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

# Cloud Run Service
resource "google_cloud_run_service" "backend" {
  name     = var.service_name
  location = var.gcp_region

  template {
    spec {
      service_account_name = google_service_account.cloud_run.email

      containers {
        image = var.image_url
        ports {
          container_port = var.container_port
        }

        # Environment variables
        env {
          name  = "SPRING_PROFILES_ACTIVE"
          value = var.environment
        }

        env {
          name  = "SPRING_DATASOURCE_URL"
          value = "jdbc:postgresql://127.0.0.1:5432/${var.database_name}?cloudSqlInstance=${var.cloud_sql_instance_connection}&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
        }

        env {
          name  = "SPRING_DATASOURCE_USERNAME"
          value = var.database_user
        }

        env {
          name = "SPRING_DATASOURCE_PASSWORD"
          value_from {
            secret_key_ref {
              name = "db-password"
              key  = "latest"
            }
          }
        }

        env {
          name = "OPENAI_API_KEY"
          value_from {
            secret_key_ref {
              name = "openai-api-key"
              key  = "latest"
            }
          }
        }

        env {
          name = "RAZORPAY_KEY_ID"
          value_from {
            secret_key_ref {
              name = "razorpay-key-id"
              key  = "latest"
            }
          }
        }

        env {
          name = "RAZORPAY_KEY_SECRET"
          value_from {
            secret_key_ref {
              name = "razorpay-key-secret"
              key  = "latest"
            }
          }
        }

        env {
          name = "JWT_SECRET"
          value_from {
            secret_key_ref {
              name = "jwt-secret"
              key  = "latest"
            }
          }
        }

        env {
          name = "RAZORPAY_WEBHOOK_SECRET"
          value_from {
            secret_key_ref {
              name = "razorpay-webhook-secret"
              key  = "latest"
            }
          }
        }

        env {
          name  = "GCS_DATA_BUCKET"
          value = var.gcs_data_bucket
        }

        env {
          name  = "GOOGLE_CLOUD_PROJECT"
          value = var.gcp_project_id
        }

        # Resource limits
        resources {
          limits = {
            cpu    = var.cpu
            memory = var.memory
          }
        }

        # Startup and liveness probes
        startup_probe {
          http_get {
            path = "/actuator/health"
            port = var.container_port
          }
          initial_delay_seconds = 30
          timeout_seconds       = 5
          period_seconds        = 10
          failure_threshold     = 3
        }

        liveness_probe {
          http_get {
            path = "/actuator/health/live"
            port = var.container_port
          }
          initial_delay_seconds = 30
          timeout_seconds       = 5
          period_seconds        = 60
          failure_threshold     = 2
        }
      }

      timeout_seconds = 3600

      # VPC Connector for Cloud SQL access
      vpc_access_connector {
        name = var.vpc_connector_id
      }
    }

    metadata {
      annotations = {
        "autoscaling.knative.dev/minScale" = tostring(var.min_instances)
        "autoscaling.knative.dev/maxScale" = tostring(var.max_instances)
        "run.googleapis.com/vpc-access-connector" = var.vpc_connector_id
        "run.googleapis.com/cloudsql-instances"   = var.cloud_sql_instance_connection
      }

      labels = {
        environment = var.environment
        app         = "interviewiq-backend"
      }
    }
  }

  traffic {
    percent         = 100
    latest_revision = true
  }

  depends_on = [
    google_project_iam_member.cloud_run_sql_client,
    google_project_iam_member.cloud_run_secret_accessor
  ]
}

# Cloud Run IAM: Allow public access
resource "google_cloud_run_service_iam_member" "cloud_run_public" {
  service  = google_cloud_run_service.backend.name
  location = google_cloud_run_service.backend.location
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# Bind secrets to Cloud Run service
resource "google_secret_manager_secret_iam_member" "db_password_secret" {
  secret_id = var.db_password_secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run.email}"
}

resource "google_secret_manager_secret_iam_member" "openai_api_key_secret" {
  secret_id = var.openai_api_key_secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run.email}"
}

resource "google_secret_manager_secret_iam_member" "razorpay_key_id_secret" {
  secret_id = var.razorpay_key_id_secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run.email}"
}

resource "google_secret_manager_secret_iam_member" "razorpay_key_secret_secret" {
  secret_id = var.razorpay_key_secret_secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run.email}"
}

resource "google_secret_manager_secret_iam_member" "jwt_secret" {
  secret_id = var.jwt_secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run.email}"
}

resource "google_secret_manager_secret_iam_member" "razorpay_webhook_secret" {
  secret_id = var.razorpay_webhook_secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run.email}"
}

# Storage bucket IAM bindings
resource "google_storage_bucket_iam_member" "gcs_data_access" {
  bucket = split("/", var.gcs_data_bucket)[length(split("/", var.gcs_data_bucket)) - 1]
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.cloud_run.email}"
}
