variable "region"              { type = string; default = "ap-south-1" }
variable "domain"              { type = string; default = "dev.interviewiq.ai" }
variable "image_tag"           { type = string; default = "latest" }
variable "db_password"         { type = string; sensitive = true }
variable "invite_secret"       { type = string; sensitive = true }
variable "openai_api_key"      { type = string; sensitive = true }
variable "razorpay_key_id"     { type = string; sensitive = true }
variable "razorpay_key_secret" { type = string; sensitive = true }
