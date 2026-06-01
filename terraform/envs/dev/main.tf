# =============================================================================
# envs/dev/main.tf
#
# Dev environment — cost-optimised, no HA, Fargate Spot, small instances.
#
# Cost estimate (ap-south-1, 730 hrs/month):
#   ECS Fargate Spot 0.5vCPU/1GB : ~$8/month
#   RDS db.t4g.micro              : ~$13/month
#   NAT Gateway (1x)              : ~$35/month  ← biggest cost in dev
#   ALB                           : ~$18/month
#   TOTAL                         : ~$74/month
#
# Tip: stop RDS + ECS nights/weekends with a Lambda scheduler to cut ~60%.
# =============================================================================

locals {
  env     = "dev"
  project = "interviewiq"
  region  = var.region
  domain  = var.domain

  common_tags = {
    Project     = local.project
    Environment = local.env
    ManagedBy   = "Terraform"
    AutoShutdown = "true"  # tag for cost-saving Lambda scheduler
  }
}

terraform {
  backend "s3" {
    bucket         = "interviewiq-terraform-state-prod"
    key            = "dev/terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "interviewiq-terraform-locks"
    encrypt        = true
  }

  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws"; version = "~> 5.0" }
  }
}

provider "aws" {
  region = var.region
  default_tags { tags = local.common_tags }
}

resource "aws_kms_key" "dev" {
  description             = "Shared KMS key for ${local.project} ${local.env}"
  deletion_window_in_days = 7
  enable_key_rotation     = true
  tags                    = local.common_tags
}

module "vpc" {
  source = "../../modules/vpc"

  project            = local.project
  env                = local.env
  region             = local.region
  vpc_cidr           = "10.1.0.0/16"  # different CIDR per env (VPC peering ready)
  az_count           = 2
  single_nat_gateway = true           # save $35/month — acceptable in dev
  kms_key_arn        = aws_kms_key.dev.arn
  tags               = local.common_tags
}

module "ecr" {
  source      = "../../modules/ecr"
  project     = local.project
  kms_key_arn = aws_kms_key.dev.arn
  tags        = local.common_tags
}

module "secrets" {
  source              = "../../modules/secrets"
  project             = local.project
  env                 = local.env
  db_password         = var.db_password
  jwt_private_key_pem = ""   # empty = ephemeral keys in dev (fine for testing)
  jwt_public_key_pem  = ""
  invite_secret       = var.invite_secret
  openai_api_key      = var.openai_api_key
  razorpay_key_id     = var.razorpay_key_id
  razorpay_key_secret = var.razorpay_key_secret
  tags                = local.common_tags
}

module "s3" {
  source               = "../../modules/s3"
  project              = local.project
  env                  = local.env
  bucket_name          = "interviewiq-dev-${data.aws_caller_identity.current.account_id}"
  cors_allowed_origins = ["*"]
  tags                 = local.common_tags
}

data "aws_caller_identity" "current" {}

module "alb" {
  source                = "../../modules/alb"
  project               = local.project
  env                   = local.env
  vpc_id                = module.vpc.vpc_id
  vpc_cidr              = module.vpc.vpc_cidr
  public_subnet_ids     = module.vpc.public_subnet_ids
  create_https_listener = false  # HTTP only in dev
  tags                  = local.common_tags
}

module "iam" {
  source              = "../../modules/iam"
  project             = local.project
  env                 = local.env
  region              = local.region
  secret_arns         = [
    module.secrets.db_password_arn,
    module.secrets.jwt_keys_arn,
    module.secrets.invite_secret_arn,
    module.secrets.openai_arn,
    module.secrets.razorpay_arn,
  ]
  secrets_kms_key_arn = module.secrets.kms_key_arn
  s3_bucket_name      = module.s3.bucket_name
  s3_kms_key_arn      = module.s3.kms_key_arn
  domain              = local.domain
  tags                = local.common_tags
}

module "rds" {
  source                = "../../modules/rds"
  project               = local.project
  env                   = local.env
  vpc_id                = module.vpc.vpc_id
  isolated_subnet_ids   = module.vpc.isolated_subnet_ids
  ecs_security_group_id = module.ecs.ecs_security_group_id
  kms_key_arn           = aws_kms_key.dev.arn
  instance_class        = "db.t4g.micro"
  db_name               = "interviewiq_dev"
  db_username           = "interviewiq"
  db_password           = var.db_password
  allocated_storage     = 20
  max_allocated_storage = 50
  multi_az              = false
  backup_retention_days = 3
  tags                  = local.common_tags
}

module "ecs" {
  source                  = "../../modules/ecs"
  project                 = local.project
  env                     = local.env
  region                  = local.region
  vpc_id                  = module.vpc.vpc_id
  private_subnet_ids      = module.vpc.private_subnet_ids
  alb_security_group_id   = module.alb.security_group_id
  target_group_arn        = module.alb.target_group_arn
  kms_key_arn             = aws_kms_key.dev.arn
  task_execution_role_arn = module.iam.task_execution_role_arn
  task_role_arn           = module.iam.task_role_arn
  ecr_repository_url      = module.ecr.backend_repository_url
  image_tag               = var.image_tag
  task_cpu                = 512
  task_memory             = 1024
  desired_count           = 1
  min_tasks               = 1
  max_tasks               = 3
  db_host                 = module.rds.endpoint
  db_name                 = "interviewiq_dev"
  db_username             = "interviewiq"
  s3_bucket_name          = module.s3.bucket_name
  domain                  = local.domain
  session_cost_paise      = 10000  # ₹100 — same as prod so billing logic is always tested correctly
  db_password_secret_arn  = module.secrets.db_password_arn
  jwt_keys_secret_arn     = module.secrets.jwt_keys_arn
  invite_secret_arn       = module.secrets.invite_secret_arn
  openai_secret_arn       = module.secrets.openai_arn
  razorpay_secret_arn     = module.secrets.razorpay_arn
  tags                    = local.common_tags
}

module "cloudwatch" {
  source = "../../modules/cloudwatch"

  project            = local.project
  env                = local.env
  kms_key_arn        = aws_kms_key.dev.arn
  alert_emails       = var.alert_emails
  ecs_cluster_name   = module.ecs.cluster_name
  ecs_service_name   = module.ecs.service_name
  ecs_log_group_name = "/ecs/${local.project}/${local.env}/backend"
  alb_arn_suffix     = module.alb.alb_arn_suffix
  rds_identifier     = "${local.project}-${local.env}-postgres"
  tags               = local.common_tags
}

# WAF protects the dev ALB from real attack traffic that hits the public endpoint.
# SES and CloudFront are intentionally skipped in dev (HTTP-only, cost saving).
module "waf" {
  source = "../../modules/waf"

  project                          = local.project
  env                              = local.env
  region                          = local.region
  alb_arn                          = module.alb.alb_arn
  alert_topic_arn                  = module.cloudwatch.alert_topic_arn
  blocked_requests_alarm_threshold = 1000  # Looser in dev — less traffic, more noise tolerance
  tags                             = local.common_tags
}

# ── Variables ─────────────────────────────────────────────────────────────────

variable "alert_emails" { type = list(string); default = [] }

# ── Outputs ───────────────────────────────────────────────────────────────────

output "alb_dns_name"    { value = module.alb.alb_dns_name }
output "ecr_backend_url" { value = module.ecr.backend_repository_url }
output "rds_endpoint"    { value = module.rds.endpoint }
output "ecs_cluster"     { value = module.ecs.cluster_name }
output "waf_acl_arn"     { value = module.waf.web_acl_arn }
