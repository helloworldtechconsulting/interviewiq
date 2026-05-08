# =============================================================================
# modules/s3/main.tf
#
# S3 bucket for InterviewIQ file storage:
#   - JD files (jd/)
#   - Candidate resumes (resume/)
#   - Interview recordings (recording/)
#   - Company logos (logo/)
#
# Security:
#   - Encrypted with KMS (CMK)
#   - No public access
#   - Pre-signed URLs for time-limited candidate access
#   - CORS configured for browser-direct uploads
#   - Lifecycle rules for cost management
# =============================================================================

resource "aws_kms_key" "s3" {
  description             = "KMS key for ${var.project} ${var.env} S3 bucket"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = merge(var.tags, { Name = "${var.project}-${var.env}-s3-key" })
}

resource "aws_kms_alias" "s3" {
  name          = "alias/${var.project}-${var.env}-s3"
  target_key_id = aws_kms_key.s3.key_id
}

# ── Main Storage Bucket ───────────────────────────────────────────────────────

resource "aws_s3_bucket" "main" {
  bucket        = var.bucket_name
  force_destroy = var.env != "prod"

  tags = merge(var.tags, { Name = var.bucket_name })
}

resource "aws_s3_bucket_versioning" "main" {
  bucket = aws_s3_bucket.main.id
  versioning_configuration {
    status = var.env == "prod" ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "main" {
  bucket = aws_s3_bucket.main.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.s3.arn
    }
    bucket_key_enabled = true # reduces KMS API calls by ~90%
  }
}

resource "aws_s3_bucket_public_access_block" "main" {
  bucket                  = aws_s3_bucket.main.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ── CORS for browser-direct uploads (pre-signed PUT) ─────────────────────────
# Spring Boot generates pre-signed URLs; the browser PUTs directly to S3.
# This avoids routing large video files through the backend.

resource "aws_s3_bucket_cors_configuration" "main" {
  bucket = aws_s3_bucket.main.id

  cors_rule {
    allowed_headers = ["Content-Type", "Content-Length", "Authorization"]
    allowed_methods = ["GET", "PUT", "POST"]
    allowed_origins = var.cors_allowed_origins
    expose_headers  = ["ETag"]
    max_age_seconds = 3600
  }
}

# ── Lifecycle Rules ────────────────────────────────────────────────────────────
# Interview recordings are large (100MB–500MB) — move to cheaper storage tier.

resource "aws_s3_bucket_lifecycle_configuration" "main" {
  bucket = aws_s3_bucket.main.id

  # Recordings: IA after 30 days, Glacier after 90 days, delete after 365 days
  rule {
    id     = "recording-lifecycle"
    status = "Enabled"
    filter { prefix = "recording/" }
    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }
    transition {
      days          = 90
      storage_class = "GLACIER_IR" # Instant Retrieval — still fast but much cheaper
    }
    expiration {
      days = 365
    }
  }

  # JD files + resumes: IA after 90 days (accessed less frequently)
  rule {
    id     = "documents-lifecycle"
    status = "Enabled"
    filter {
      or {
        prefix = "jd/"
        prefix = "resume/"
      }
    }
    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }
  }

  # Logos rarely change — IA immediately viable if > 128KB
  rule {
    id     = "logo-lifecycle"
    status = "Enabled"
    filter { prefix = "logo/" }
    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }
  }

  # Clean up incomplete multipart uploads (browser crash mid-upload)
  rule {
    id     = "cleanup-multipart"
    status = "Enabled"
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
    filter {}
  }
}

# ── Bucket Policy ─────────────────────────────────────────────────────────────
# Deny any access not using TLS; deny unencrypted uploads.

resource "aws_s3_bucket_policy" "main" {
  bucket = aws_s3_bucket.main.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyNonSSL"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = ["${aws_s3_bucket.main.arn}", "${aws_s3_bucket.main.arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      },
      {
        Sid       = "DenyUnencryptedPuts"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.main.arn}/*"
        Condition = {
          StringNotEquals = {
            "s3:x-amz-server-side-encryption" = "aws:kms"
          }
        }
      }
    ]
  })
}
