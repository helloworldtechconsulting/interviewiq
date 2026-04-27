variable "project" { type = string }
variable "env"     { type = string }
variable "tags"    { type = map(string); default = {} }

variable "db_password"         { type = string; sensitive = true }
variable "jwt_private_key_pem" { type = string; sensitive = true }
variable "jwt_public_key_pem"  { type = string; sensitive = true }
variable "invite_secret"       { type = string; sensitive = true }
variable "openai_api_key"      { type = string; sensitive = true }
variable "razorpay_key_id"     { type = string; sensitive = true }
variable "razorpay_key_secret" { type = string; sensitive = true }
