# =============================================================================
# Configuration and secrets
#
# Arch v4.0 §3: "K8s Secrets + External Secrets Operator — the backend swaps per
# cloud; the app only ever sees env vars." That is what keeps the application
# itself cloud-agnostic: it reads SPRING_DATASOURCE_URL, not a cloud SDK.
# =============================================================================

resource "kubernetes_namespace_v1" "app" {
  metadata {
    name   = var.namespace
    labels = local.common_labels
  }
}

resource "kubernetes_config_map_v1" "app" {
  metadata {
    name      = "interviewiq-config"
    namespace = var.namespace
  }

  data = {
    # ── Object storage (S3-compatible) ─────────────────────────────────────
    OBJECT_STORAGE_ENDPOINT = var.object_storage_endpoint
    OBJECT_STORAGE_BUCKET   = var.object_storage_bucket
    OBJECT_STORAGE_REGION   = var.object_storage_region

    # ── Application ────────────────────────────────────────────────────────
    APP_FRONTEND_BASE_URL             = "https://${replace(var.api_host, "api.", "app.")}"
    APP_SECURITY_CORS_ALLOWED_ORIGINS = join(",", var.frontend_origins)
    APP_SCHEDULING_BUCKET_CAPACITY    = tostring(var.bucket_capacity)

    # Java 21 virtual threads. PRD §6.2 lists this as a decision, not a tuning
    # knob: the parallel evaluation design in §7.5.5 depends on it, and so does
    # the ScopedValue span nesting in the domain event log (INTIQ-98).
    SPRING_THREADS_VIRTUAL_ENABLED = "true"

    JAVA_TOOL_OPTIONS = join(" ", [
      "-XX:MaxRAMPercentage=75.0",
      "-XX:+UseG1GC",
      "-XX:+ExitOnOutOfMemoryError",
    ])
  }
}

# =============================================================================
# Secrets — materialised by the External Secrets Operator
#
# The ExternalSecret below names the keys; the values come from whatever backend
# the platform provides (Secret Manager on GCP, Vault on OCI, and so on). The
# application never learns which.
# =============================================================================

resource "kubernetes_manifest" "app_external_secret" {
  manifest = {
    apiVersion = "external-secrets.io/v1beta1"
    kind       = "ExternalSecret"
    metadata = {
      name      = "interviewiq-secrets"
      namespace = var.namespace
    }
    spec = {
      refreshInterval = "1h"
      secretStoreRef = {
        name = var.secret_store_name
        kind = "ClusterSecretStore"
      }
      target = {
        name           = "interviewiq-secrets"
        creationPolicy = "Owner"
      }
      data = [
        # RSA signing keys. These MUST be injected rather than generated at
        # startup: an ephemeral key pair invalidates every live token on every
        # deploy, which was a ship-blocking defect in staging.
        { secretKey = "APP_SECURITY_JWT_PRIVATE_KEY_PEM", remoteRef = { key = "interviewiq/jwt-private-key" } },
        { secretKey = "APP_SECURITY_JWT_PUBLIC_KEY_PEM", remoteRef = { key = "interviewiq/jwt-public-key" } },
        # Same reasoning: a regenerated invite secret invalidates every
        # outstanding candidate invite link.
        { secretKey = "APP_SECURITY_INVITE_SECRET", remoteRef = { key = "interviewiq/invite-secret" } },

        { secretKey = "OPENAI_API_KEY", remoteRef = { key = "interviewiq/openai-api-key" } },
        { secretKey = "ANTHROPIC_API_KEY", remoteRef = { key = "interviewiq/anthropic-api-key" } },

        { secretKey = "RAZORPAY_KEY_ID", remoteRef = { key = "interviewiq/razorpay-key-id" } },
        { secretKey = "RAZORPAY_KEY_SECRET", remoteRef = { key = "interviewiq/razorpay-key-secret" } },

        { secretKey = "SMTP_HOST", remoteRef = { key = "interviewiq/smtp-host" } },
        { secretKey = "SMTP_USERNAME", remoteRef = { key = "interviewiq/smtp-username" } },
        { secretKey = "SMTP_PASSWORD", remoteRef = { key = "interviewiq/smtp-password" } },
        { secretKey = "MAIL_FROM_ADDRESS", remoteRef = { key = "interviewiq/mail-from-address" } },
      ]
    }
  }

  depends_on = [kubernetes_namespace_v1.app]
}

# =============================================================================
# Platform-supplied secrets
#
# These come from the platform module's output contract rather than from the
# secret store, because they are created by the same apply that creates the
# database and bucket.
# =============================================================================

resource "kubernetes_secret_v1" "app" {
  metadata {
    name      = "interviewiq-platform-secrets"
    namespace = var.namespace
  }

  data = {
    SPRING_DATASOURCE_URL      = var.postgres_url
    SPRING_DATASOURCE_USERNAME = var.postgres_username
    SPRING_DATASOURCE_PASSWORD = var.postgres_password

    OBJECT_STORAGE_ACCESS_KEY = var.object_storage_access_key
    OBJECT_STORAGE_SECRET_KEY = var.object_storage_secret_key

    # libpq-style URL for the KEDA scaler, which connects independently of the
    # application and cannot read a JDBC URL.
    KEDA_POSTGRES_CONNECTION = local.keda_connection_string
  }

  type = "Opaque"

  depends_on = [kubernetes_namespace_v1.app]
}

locals {
  # jdbc:postgresql://host:port/db → postgresql://user:pass@host:port/db
  postgres_host_and_db = replace(var.postgres_url, "jdbc:postgresql://", "")

  keda_connection_string = format(
    "postgresql://%s:%s@%s?sslmode=require",
    var.postgres_username,
    urlencode(var.postgres_password),
    local.postgres_host_and_db,
  )
}
