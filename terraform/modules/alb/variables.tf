variable "project"                { type = string }
variable "env"                    { type = string }
variable "vpc_id"                 { type = string }
variable "vpc_cidr"               { type = string }
variable "public_subnet_ids"      { type = list(string) }
variable "create_https_listener"  { type = bool; default = true }
variable "acm_certificate_arn"    { type = string; default = "" }
variable "tags"                   { type = map(string); default = {} }
