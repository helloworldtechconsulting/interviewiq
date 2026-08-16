variable "project"                 { type = string }
variable "env"                     { type = string }
variable "region"                  { type = string }
variable "vpc_id"                  { type = string }
variable "private_subnet_ids"      { type = list(string) }
variable "alb_security_group_id"   { type = string }
variable "target_group_arn"        { type = string }
variable "kms_key_arn"             { type = string }
variable "task_execution_role_arn" { type = string }
variable "task_role_arn"           { type = string }
variable "ecr_repository_url"      { type = string }
variable "image_tag"               { type = string; default = "latest" }

variable "task_cpu"    { type = number; default = 512;  description = "512=0.5vCPU, 1024=1vCPU, 2048=2vCPU" }
variable "task_memory" { type = number; default = 1024; description = "MB — must be valid Fargate pair" }
variable "desired_count" { type = number; default = 1 }
variable "min_tasks"     { type = number; default = 1 }
variable "max_tasks"     { type = number; default = 10 }

variable "db_host"     { type = string }
variable "db_name"     { type = string }
variable "db_username" { type = string }
variable "s3_bucket_name" { type = string }
variable "domain"         { type = string }
variable "session_cost_paise" {
  type        = number
  default     = 10000
  description = "Cost per completed interview in paise. 10000 = ₹100. Must never be below 10000 in prod."
  validation {
    condition     = var.session_cost_paise >= 1000
    error_message = "session_cost_paise must be at least 1000 paise (₹10). Check your billing configuration."
  }
}

variable "db_password_secret_arn" { type = string }
variable "jwt_keys_secret_arn"    { type = string }
variable "invite_secret_arn"      { type = string }
variable "openai_secret_arn"      { type = string }
variable "razorpay_secret_arn"    { type = string }

variable "tags" { type = map(string); default = {} }
