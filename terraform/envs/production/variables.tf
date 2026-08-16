variable "project_id" { type = string }

variable "region" {
  type    = string
  default = "asia-south1" # Mumbai — candidate data at rest in India (PRD §8)
}

variable "zone" {
  type    = string
  default = "asia-south1-a"
}

variable "zone_name" {
  description = "Registered domain, e.g. interviewiq.in."
  type        = string
}

variable "image" {
  description = "GHCR image. Cloud-neutral, reversing the ECR decision (INTIQ-52)."
  type        = string
}

variable "cloudflare_zone_id" { type = string }
variable "ingress_ip" { type = string }
variable "spa_origin_hostname" { type = string }

variable "r2_endpoint" { type = string }
variable "r2_bucket" { type = string }

variable "r2_access_key" {
  type      = string
  sensitive = true
}

variable "r2_secret_key" {
  type      = string
  sensitive = true
}

variable "grafana_admin_password" {
  description = "Grafana admin login. Sourced from the deployment secret store, never committed."
  type        = string
  sensitive   = true
}
