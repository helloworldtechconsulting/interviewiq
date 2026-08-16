variable "project"              { type = string }
variable "env"                  { type = string }
variable "bucket_name"          { type = string }
variable "cors_allowed_origins" { type = list(string); default = ["*"] }
variable "tags"                 { type = map(string); default = {} }
