variable "project"            { type = string }
variable "env"                { type = string }
variable "kms_key_arn"        { type = string }
variable "alert_emails"       { type = list(string); default = [] }
variable "ecs_cluster_name"   { type = string }
variable "ecs_service_name"   { type = string }
variable "ecs_log_group_name" { type = string }
variable "alb_arn_suffix"     { type = string }
variable "rds_identifier"     { type = string }
variable "tags"               { type = map(string); default = {} }
