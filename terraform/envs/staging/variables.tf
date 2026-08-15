variable "compartment_id" { type = string }
variable "availability_domain" { type = string }

variable "region" {
  type    = string
  default = "ap-mumbai-1" # candidate data at rest in India (PRD §8)
}

variable "node_image_id" {
  description = "Oracle Linux ARM64 image OCID for the region."
  type        = string
}

variable "zone_name" { type = string }

variable "image" {
  description = "GHCR image — the SAME image production runs."
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
