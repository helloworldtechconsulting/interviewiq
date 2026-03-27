output "vpc_id" {
  description = "VPC network ID"
  value       = google_compute_network.vpc.id
}

output "vpc_name" {
  description = "VPC network name"
  value       = google_compute_network.vpc.name
}

output "subnet_id" {
  description = "Subnet ID"
  value       = google_compute_subnetwork.subnet.id
}

output "subnet_name" {
  description = "Subnet name"
  value       = google_compute_subnetwork.subnet.name
}

output "vpc_connector_id" {
  description = "VPC Connector ID"
  value       = google_vpc_access_connector.connector.id
}

output "vpc_connector_name" {
  description = "VPC Connector name"
  value       = google_vpc_access_connector.connector.name
}

output "private_service_connection_id" {
  description = "Private Service Connection ID"
  value       = google_service_networking_connection.private_vpc_connection.id
}
