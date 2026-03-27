# Database Password Secret
resource "google_secret_manager_secret" "db_password" {
  secret_id = "interviewiq-${var.environment}-db-password"

  replication {
    automatic = true
  }

  labels = {
    environment = var.environment
    app         = "interviewiq"
  }
}

resource "google_secret_manager_secret_version" "db_password_version" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = var.db_password
}

# OpenAI API Key Secret
resource "google_secret_manager_secret" "openai_api_key" {
  secret_id = "interviewiq-${var.environment}-openai-api-key"

  replication {
    automatic = true
  }

  labels = {
    environment = var.environment
    app         = "interviewiq"
  }
}

resource "google_secret_manager_secret_version" "openai_api_key_version" {
  secret      = google_secret_manager_secret.openai_api_key.id
  secret_data = var.openai_api_key
}

# Razorpay Key ID Secret
resource "google_secret_manager_secret" "razorpay_key_id" {
  secret_id = "interviewiq-${var.environment}-razorpay-key-id"

  replication {
    automatic = true
  }

  labels = {
    environment = var.environment
    app         = "interviewiq"
  }
}

resource "google_secret_manager_secret_version" "razorpay_key_id_version" {
  secret      = google_secret_manager_secret.razorpay_key_id.id
  secret_data = var.razorpay_key_id
}

# Razorpay Key Secret
resource "google_secret_manager_secret" "razorpay_key_secret" {
  secret_id = "interviewiq-${var.environment}-razorpay-key-secret"

  replication {
    automatic = true
  }

  labels = {
    environment = var.environment
    app         = "interviewiq"
  }
}

resource "google_secret_manager_secret_version" "razorpay_key_secret_version" {
  secret      = google_secret_manager_secret.razorpay_key_secret.id
  secret_data = var.razorpay_key_secret
}

# Razorpay Webhook Secret
resource "google_secret_manager_secret" "razorpay_webhook_secret" {
  secret_id = "interviewiq-${var.environment}-razorpay-webhook-secret"

  replication {
    automatic = true
  }

  labels = {
    environment = var.environment
    app         = "interviewiq"
  }
}

resource "google_secret_manager_secret_version" "razorpay_webhook_secret_version" {
  secret      = google_secret_manager_secret.razorpay_webhook_secret.id
  secret_data = var.razorpay_webhook_secret
}

# JWT Secret
resource "google_secret_manager_secret" "jwt_secret" {
  secret_id = "interviewiq-${var.environment}-jwt-secret"

  replication {
    automatic = true
  }

  labels = {
    environment = var.environment
    app         = "interviewiq"
  }
}

resource "google_secret_manager_secret_version" "jwt_secret_version" {
  secret      = google_secret_manager_secret.jwt_secret.id
  secret_data = var.jwt_secret
}
