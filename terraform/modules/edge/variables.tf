variable "zone_id" { type = string }
variable "zone_name" { type = string }

variable "api_subdomain" {
  type    = string
  default = "api"
}

variable "app_subdomain" {
  type    = string
  default = "app"
}

variable "ingress_ip" {
  description = "Public IP of the Kubernetes ingress controller. The one value that changes when the cloud does."
  type        = string
}

variable "spa_origin_hostname" {
  description = "Object-storage hostname serving the static SPA."
  type        = string
}

variable "enforce_webhook_source_ips" {
  description = <<-EOT
    Restrict the Razorpay webhook to Razorpay source IPs. Off by default because
    the list changes without notice and a stale list silently drops payment
    confirmations — the application's HMAC verification fails closed regardless.
  EOT
  type        = bool
  default     = false
}
