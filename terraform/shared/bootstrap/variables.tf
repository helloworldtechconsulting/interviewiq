variable "region" {
  description = "AWS region for bootstrap resources"
  type        = string
  default     = "ap-south-1"
}

variable "state_bucket_name" {
  description = "Globally unique S3 bucket name for Terraform state"
  type        = string
  default     = "interviewiq-terraform-state-prod" # CHANGE: must be globally unique
}

variable "lock_table_name" {
  description = "DynamoDB table name for state locking"
  type        = string
  default     = "interviewiq-terraform-locks"
}
