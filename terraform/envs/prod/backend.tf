# =============================================================================
# envs/prod/backend.tf
#
# Remote state stored in S3 (created by shared/bootstrap).
# DynamoDB ensures only one person/pipeline can apply at a time.
# State is KMS-encrypted.
# =============================================================================

terraform {
  backend "s3" {
    bucket         = "interviewiq-terraform-state-prod" # from bootstrap output
    key            = "prod/terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "interviewiq-terraform-locks"
    encrypt        = true
    # kms_key_id   = "alias/interviewiq-terraform-state"  # uncomment if CMK
  }

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

  default_tags {
    tags = local.common_tags
  }
}
