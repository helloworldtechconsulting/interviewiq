variable "project"               { type = string }
variable "env"                   { type = string }
variable "vpc_id"                { type = string }
variable "isolated_subnet_ids"   { type = list(string) }
variable "ecs_security_group_id" { type = string }
variable "kms_key_arn"           { type = string }

variable "instance_class" {
  type    = string
  default = "db.t4g.micro"
  description = "db.t4g.micro (dev), db.t4g.small (staging), db.t4g.medium (prod)"
}
variable "db_name"     { type = string; default = "interviewiq" }
variable "db_username" { type = string; default = "interviewiq" }
variable "db_password" { type = string; sensitive = true }

variable "allocated_storage"     { type = number; default = 20 }
variable "max_allocated_storage" { type = number; default = 100 }
variable "multi_az"              { type = bool;   default = false }
variable "backup_retention_days" { type = number; default = 7 }
variable "create_read_replica"   { type = bool;   default = false }
variable "replica_instance_class"{ type = string; default = "db.t4g.micro" }
variable "tags"                  { type = map(string); default = {} }
