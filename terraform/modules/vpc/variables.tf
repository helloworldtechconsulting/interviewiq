variable "project"          { type = string; default = "interviewiq" }
variable "env"              { type = string }
variable "region"           { type = string; default = "ap-south-1" }
variable "vpc_cidr"         { type = string; default = "10.0.0.0/16" }
variable "az_count"         { type = number; default = 2 }
variable "single_nat_gateway" { type = bool; default = false; description = "true for dev/staging to save cost" }
variable "kms_key_arn"      { type = string; description = "KMS key for CloudWatch log encryption" }
variable "tags"             { type = map(string); default = {} }
