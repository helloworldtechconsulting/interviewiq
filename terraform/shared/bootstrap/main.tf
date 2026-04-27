# =============================================================================
# shared/bootstrap/main.tf
#
# Run ONCE before any env workspace:
#   cd terraform/shared/bootstrap
#   terraform init && terraform apply
#
# Creates:
#   - S3 bucket for Terraform remote state (versioned + encrypted)
#   - DynamoDB table for state locking
#   - KMS key for state encryption
#
# After this runs, copy the bucket/table names into each env's backend.tf
# =============================================================================

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

# ── KMS key for state encryption ─────────────────────────────────────────────

resource "aws_kms_key" "terraform_state" {
  description             = "KMS key for InterviewIQ Terraform state encryption"
  deletion_window_in_days = 10
  enable_key_rotation     = true

  tags = {
    Name    = "interviewiq-terraform-state-key"
    Project = "interviewiq"
  }
}

resource "aws_kms_alias" "terraform_state" {
  name          = "alias/interviewiq-terraform-state"
  target_key_id = aws_kms_key.terraform_state.key_id
}

# ── S3 bucket for remote state ────────────────────────────────────────────────

resource "aws_s3_bucket" "terraform_state" {
  bucket = var.state_bucket_name

  # Prevent accidental deletion of state
  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Name    = "interviewiq-terraform-state"
    Project = "interviewiq"
  }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.terraform_state.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket                  = aws_s3_bucket.terraform_state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  rule {
    id     = "expire-old-versions"
    status = "Enabled"
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}

# ── DynamoDB table for state locking ─────────────────────────────────────────

resource "aws_dynamodb_table" "terraform_locks" {
  name         = var.lock_table_name
  billing_mode = "PAY_PER_REQUEST" # no capacity planning needed for locking
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.terraform_state.arn
  }

  point_in_time_recovery {
    enabled = true
  }

  tags = {
    Name    = "interviewiq-terraform-locks"
    Project = "interviewiq"
  }
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "state_bucket_name" {
  value       = aws_s3_bucket.terraform_state.bucket
  description = "Paste this into each env's backend.tf"
}

output "lock_table_name" {
  value       = aws_dynamodb_table.terraform_locks.name
  description = "Paste this into each env's backend.tf"
}

output "kms_key_arn" {
  value       = aws_kms_key.terraform_state.arn
  description = "KMS ARN for state encryption"
}
