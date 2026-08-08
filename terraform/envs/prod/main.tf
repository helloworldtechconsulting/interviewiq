locals {
  env     = "prod"
  project = "interviewiq"
  region  = var.region
  domain  = var.domain

  common_tags = {
    Project     = local.project
    Environment = local.env
    ManagedBy   = "Terraform"
    Owner       = "platform-team"
  }
}

terraform {
  backend "s3" {
    bucket         = "interviewiq-terraform-state-prod"
    key            = "prod/terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "interviewiq-terraform-locks"
    encrypt        = true
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
  default_tags { tags = local.common_tags }
}

provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
  default_tags { tags = local.common_tags }
}

variable "region"              { type = string; default = "ap-south-1" }
variable "domain"              { type = string; default = "interviewiq.ai" }
variable "vpc_cidr"            { type = string; default = "10.0.0.0/16" }
variable "image_tag"           { type = string; default = "latest" }
variable "alert_emails"        { type = list(string); default = [] }
variable "db_password"         { type = string; sensitive = true }
variable "jwt_private_key_pem" { type = string; sensitive = true }
variable "jwt_public_key_pem"  { type = string; sensitive = true }
variable "invite_secret"       { type = string; sensitive = true }
variable "openai_api_key"      { type = string; sensitive = true }
variable "razorpay_key_id"     { type = string; sensitive = true }
variable "razorpay_key_secret" { type = string; sensitive = true }

data "aws_caller_identity" "current" {}

data "aws_route53_zone" "main" {
  name         = "${local.domain}."
  private_zone = false
}

resource "aws_kms_key" "cloudwatch" {
  description             = "KMS for ${local.project} ${local.env} CloudWatch logs"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = local.common_tags
}

resource "aws_kms_alias" "cloudwatch" {
  name          = "alias/${local.project}-${local.env}-cloudwatch"
  target_key_id = aws_kms_key.cloudwatch.key_id
}

resource "aws_kms_key" "ecr" {
  description             = "KMS for ${local.project} ${local.env} ECR"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = local.common_tags
}

resource "aws_kms_alias" "ecr" {
  name          = "alias/${local.project}-${local.env}-ecr"
  target_key_id = aws_kms_key.ecr.key_id
}

resource "aws_acm_certificate" "alb" {
  domain_name               = "*.${local.domain}"
  subject_alternative_names = [local.domain]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = local.common_tags
}

resource "aws_route53_record" "alb_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.alb.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = data.aws_route53_zone.main.zone_id
}

resource "aws_acm_certificate_validation" "alb" {
  certificate_arn         = aws_acm_certificate.alb.arn
  validation_record_fqdns = [for record in aws_route53_record.alb_cert_validation : record.fqdn]
}

module "vpc" {
  source = "../../modules/vpc"

  project            = local.project
  env                = local.env
  region             = local.region
  vpc_cidr           = var.vpc_cidr
  az_count           = 3
  single_nat_gateway = false
  kms_key_arn        = aws_kms_key.cloudwatch.arn
  tags               = local.common_tags
}

module "ecr" {
  source = "../../modules/ecr"

  project             = local.project
  kms_key_arn         = aws_kms_key.ecr.arn
  allowed_account_ids = []
  tags                = local.common_tags
}

module "secrets" {
  source = "../../modules/secrets"

  project             = local.project
  env                 = local.env
  db_password         = var.db_password
  jwt_private_key_pem = var.jwt_private_key_pem
  jwt_public_key_pem  = var.jwt_public_key_pem
  invite_secret       = var.invite_secret
  openai_api_key      = var.openai_api_key
  razorpay_key_id     = var.razorpay_key_id
  razorpay_key_secret = var.razorpay_key_secret
  tags                = local.common_tags
}

module "s3" {
  source = "../../modules/s3"

  project              = local.project
  env                  = local.env
  bucket_name          = "interviewiq-prod-${data.aws_caller_identity.current.account_id}"
  cors_allowed_origins = ["https://${local.domain}", "https://www.${local.domain}"]
  tags                 = local.common_tags
}

module "alb" {
  source = "../../modules/alb"

  project               = local.project
  env                   = local.env
  vpc_id                = module.vpc.vpc_id
  vpc_cidr              = module.vpc.vpc_cidr
  public_subnet_ids     = module.vpc.public_subnet_ids
  create_https_listener = true
  acm_certificate_arn   = aws_acm_certificate_validation.alb.certificate_arn
  tags                  = local.common_tags
}

module "iam" {
  source = "../../modules/iam"

  project    = local.project
  env        = local.env
  region     = local.region
  secret_arns = [
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
  source = "../../modules/rds"

  project               = local.project
  env                   = local.env
  vpc_id                = module.vpc.vpc_id
  isolated_subnet_ids   = module.vpc.isolated_subnet_ids
  ecs_security_group_id = module.ecs.ecs_security_group_id
  kms_key_arn           = module.secrets.kms_key_arn
  instance_class        = "db.t4g.medium"
  db_name               = "interviewiq"
  db_username           = "interviewiq"
  db_password           = var.db_password
  allocated_storage     = 50
  max_allocated_storage = 500
  multi_az              = true
  backup_retention_days = 30
  create_read_replica   = false
  tags                  = local.common_tags
}

module "ecs" {
  source = "../../modules/ecs"

  project                 = local.project
  env                     = local.env
  region                  = local.region
  vpc_id                  = module.vpc.vpc_id
  private_subnet_ids      = module.vpc.private_subnet_ids
  alb_security_group_id   = module.alb.security_group_id
  target_group_arn        = module.alb.target_group_arn
  kms_key_arn             = aws_kms_key.cloudwatch.arn
  task_execution_role_arn = module.iam.task_execution_role_arn
  task_role_arn           = module.iam.task_role_arn
  ecr_repository_url      = module.ecr.backend_repository_url
  image_tag               = var.image_tag

  task_cpu    = 1024
  task_memory = 2048

  desired_count = 2
  min_tasks     = 2
  max_tasks     = 10

  db_host            = module.rds.endpoint
  db_name            = "interviewiq"
  db_username        = "interviewiq"
  s3_bucket_name     = module.s3.bucket_name
  domain             = local.domain
  session_cost_paise = 10000

  db_password_secret_arn = module.secrets.db_password_arn
  jwt_keys_secret_arn    = module.secrets.jwt_keys_arn
  invite_secret_arn      = module.secrets.invite_secret_arn
  openai_secret_arn      = module.secrets.openai_arn
  razorpay_secret_arn    = module.secrets.razorpay_arn

  tags = local.common_tags
}

module "cloudwatch" {
  source = "../../modules/cloudwatch"

  project            = local.project
  env                = local.env
  kms_key_arn        = aws_kms_key.cloudwatch.arn
  alert_emails       = var.alert_emails
  ecs_cluster_name   = module.ecs.cluster_name
  ecs_service_name   = module.ecs.service_name
  ecs_log_group_name = "/ecs/${local.project}/${local.env}/backend"
  alb_arn_suffix     = module.alb.alb_arn_suffix
  rds_identifier     = "${local.project}-${local.env}-postgres"
  tags               = local.common_tags
}

module "ses" {
  source = "../../modules/ses"

  project         = local.project
  env             = local.env
  region          = local.region
  domain          = local.domain
  route53_zone_id = data.aws_route53_zone.main.zone_id
  alert_topic_arn = module.cloudwatch.alert_topic_arn
  dmarc_policy    = "reject"
  tags            = local.common_tags
}

module "waf" {
  source = "../../modules/waf"

  project                          = local.project
  env                              = local.env
  region                           = local.region
  alb_arn                          = module.alb.alb_arn
  alert_topic_arn                  = module.cloudwatch.alert_topic_arn
  blocked_requests_alarm_threshold = 200
  tags                             = local.common_tags
}

module "cloudfront" {
  source = "../../modules/cloudfront"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  project         = local.project
  env             = local.env
  domain          = local.domain
  spa_bucket_name = "interviewiq-prod-spa-${data.aws_caller_identity.current.account_id}"
  alb_dns_name    = module.alb.alb_dns_name
  route53_zone_id = data.aws_route53_zone.main.zone_id
  tags            = local.common_tags
}

resource "aws_route53_record" "api" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "api.${local.domain}"
  type    = "A"

  alias {
    name                   = module.alb.alb_dns_name
    zone_id                = module.alb.alb_zone_id
    evaluate_target_health = true
  }
}

output "alb_dns_name"      { value = module.alb.alb_dns_name }
output "api_url"           { value = "https://api.${local.domain}" }
output "app_url"           { value = "https://${local.domain}" }
output "ecr_backend_url"   { value = module.ecr.backend_repository_url }
output "rds_endpoint"      { value = module.rds.endpoint }
output "s3_bucket"         { value = module.s3.bucket_name }
output "spa_bucket"        { value = module.cloudfront.spa_bucket_name }
output "cf_distribution"   { value = module.cloudfront.distribution_id }
output "ecs_cluster_name"  { value = module.ecs.cluster_name }
output "waf_web_acl_arn"   { value = module.waf.web_acl_arn }
output "ses_mail_from"     { value = module.ses.mail_from_domain }
