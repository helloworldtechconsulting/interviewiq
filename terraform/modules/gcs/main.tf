# Data Bucket - for storing interview recordings and documents
resource "google_storage_bucket" "data" {
  name          = var.data_bucket
  location      = var.gcp_region
  force_destroy = false

  uniform_bucket_level_access = true

  versioning {
    enabled = true
  }

  lifecycle_rule {
    condition {
      age_days          = 7
      prefix = "recordings/"
    }
    action {
      type = "Delete"
    }
  }

  lifecycle_rule {
    condition {
      age_days = 30
      prefix   = "data/"
    }
    action {
      type          = "SetStorageClass"
      storage_class = "NEARLINE"
    }
  }

  lifecycle_rule {
    condition {
      age_days             = 90
      matches_storage_class = ["NEARLINE"]
    }
    action {
      type          = "SetStorageClass"
      storage_class = "COLDLINE"
    }
  }

  labels = {
    environment = var.environment
    purpose     = "data-storage"
  }
}

# Frontend Bucket - for static website hosting
resource "google_storage_bucket" "frontend" {
  name          = var.frontend_bucket
  location      = var.gcp_region
  force_destroy = false

  uniform_bucket_level_access = false

  website {
    main_page_suffix = "index.html"
    not_found_page   = "index.html"
  }

  cors {
    origin          = ["*"]
    method          = ["GET", "HEAD", "OPTIONS"]
    response_header = ["Content-Type", "Access-Control-Allow-Origin"]
    max_age_seconds = 3600
  }

  labels = {
    environment = var.environment
    purpose     = "frontend-hosting"
  }
}

# Public access to frontend bucket
resource "google_storage_bucket_iam_member" "frontend_public" {
  bucket = google_storage_bucket.frontend.name
  role   = "roles/storage.objectViewer"
  member = "allUsers"
}
