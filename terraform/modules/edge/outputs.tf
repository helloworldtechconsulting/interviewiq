output "api_hostname" {
  value = "${var.api_subdomain}.${var.zone_name}"
}

output "app_hostname" {
  value = "${var.app_subdomain}.${var.zone_name}"
}
