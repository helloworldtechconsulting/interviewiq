variable "region"   { type = string; default = "ap-south-1" }
variable "domain"   { type = string; description = "e.g. interviewiq.ai" }
variable "vpc_cidr" { type = string; default = "10.0.0.0/16" }
variable "image_tag"{ type = string; default = "latest" }

variable "alert_emails" {
  type        = list(string)
  description = "Email addresses for CloudWatch alarm notifications"
  default     = []
}

# ── Sensitive — injected via CI/CD env vars or AWS Secrets Manager ──────────
variable "db_password"         { type = string; sensitive = true }
variable "jwt_private_key_pem" { type = string; sensitive = true }
variable "jwt_public_key_pem"  { type = string; sensitive = true }
variable "invite_secret"       { type = string; sensitive = true }
variable "openai_api_key"      { type = string; sensitive = true }
variable "razorpay_key_id"     { type = string; sensitive = true }
variable "razorpay_key_secret" { type = string; sensitive = true }
