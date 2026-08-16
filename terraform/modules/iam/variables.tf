variable "project"            { type = string }
variable "env"                { type = string }
variable "region"             { type = string }
variable "secret_arns"        { type = list(string) }
variable "secrets_kms_key_arn"{ type = string }
variable "s3_bucket_name"     { type = string }
variable "s3_kms_key_arn"     { type = string }
variable "domain"             { type = string }
variable "tags"               { type = map(string); default = {} }
