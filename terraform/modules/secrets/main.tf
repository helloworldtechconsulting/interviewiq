# =============================================================================
# modules/secrets/main.tf
#
# AWS Secrets Manager for InterviewIQ credentials.
# ECS task definition references these secrets by ARN — values are NEVER
# baked into container images or Terraform state in plaintext.
#
# Secrets created:
#   - DB password
#   - JWT RSA key pair (private + public PEM)
#   - Invite HMAC secret
#   - OpenAI API key
#   - Razorpay key + secret
#   - AWS SES credentials (if not using IAM role — prefer role in prod)
# =============================================================================

locals {
  secret_prefix = "${var.project}/${var.env}"
}

# ── KMS key for secrets ───────────────────────────────────────────────────────

resource "aws_kms_key" "secrets" {
  description             = "KMS key for ${var.project} ${var.env} Secrets Manager"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = merge(var.tags, { Name = "${var.project}-${var.env}-secrets-key" })
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${var.project}-${var.env}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}

# ── Database Password ─────────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "db_password" {
  name                    = "${local.secret_prefix}/db-password"
  description             = "RDS PostgreSQL password for ${var.env}"
  kms_key_id              = aws_kms_key.secrets.arn
  recovery_window_in_days = var.env == "prod" ? 30 : 0
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = var.db_password
}

# ── JWT RSA Key Pair ──────────────────────────────────────────────────────────
# Store as a JSON object so both keys can be versioned together atomically.
# ECS task uses these via APP_SECURITY_JWT_PRIVATE_KEY_PEM env injection.

resource "aws_secretsmanager_secret" "jwt_keys" {
  name                    = "${local.secret_prefix}/jwt-key-pair"
  description             = "RSA-2048 PEM key pair for JWT signing (${var.env})"
  kms_key_id              = aws_kms_key.secrets.arn
  recovery_window_in_days = var.env == "prod" ? 30 : 0
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "jwt_keys" {
  secret_id = aws_secretsmanager_secret.jwt_keys.id
  # Pass real PEM values via tfvars or CI secrets — NEVER commit to repo
  secret_string = jsonencode({
    private_key_pem = var.jwt_private_key_pem
    public_key_pem  = var.jwt_public_key_pem
  })
}

# ── Invite HMAC Secret ────────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "invite_secret" {
  name                    = "${local.secret_prefix}/invite-secret"
  description             = "HMAC-SHA256 secret for candidate invite tokens (${var.env})"
  kms_key_id              = aws_kms_key.secrets.arn
  recovery_window_in_days = var.env == "prod" ? 30 : 0
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "invite_secret" {
  secret_id     = aws_secretsmanager_secret.invite_secret.id
  secret_string = var.invite_secret
}

# ── OpenAI API Key ────────────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "openai" {
  name                    = "${local.secret_prefix}/openai-api-key"
  description             = "OpenAI API key for question generation + evaluation"
  kms_key_id              = aws_kms_key.secrets.arn
  recovery_window_in_days = var.env == "prod" ? 30 : 0
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "openai" {
  secret_id     = aws_secretsmanager_secret.openai.id
  secret_string = var.openai_api_key
}

# ── Razorpay Credentials ──────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "razorpay" {
  name                    = "${local.secret_prefix}/razorpay"
  description             = "Razorpay key_id + key_secret for billing webhooks"
  kms_key_id              = aws_kms_key.secrets.arn
  recovery_window_in_days = var.env == "prod" ? 30 : 0
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "razorpay" {
  secret_id = aws_secretsmanager_secret.razorpay.id
  secret_string = jsonencode({
    key_id     = var.razorpay_key_id
    key_secret = var.razorpay_key_secret
  })
}

# ── Secret rotation (prod only — rotate DB password automatically) ────────────
# Requires the SecretsManager rotation Lambda for RDS single-user rotation.
# Uncomment once baseline is stable.
#
# resource "aws_secretsmanager_secret_rotation" "db_password" {
#   count               = var.env == "prod" ? 1 : 0
#   secret_id           = aws_secretsmanager_secret.db_password.id
#   rotation_lambda_arn = var.rotation_lambda_arn
#   rotation_rules {
#     automatically_after_days = 30
#   }
# }
