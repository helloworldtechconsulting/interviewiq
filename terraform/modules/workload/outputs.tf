output "web_service_name" {
  description = "ClusterIP Service the ingress routes to."
  value       = kubernetes_service_v1.web.metadata[0].name
}

output "api_host" {
  description = "Public API hostname. Cloudflare points at the ingress for this name."
  value       = var.api_host
}

output "namespace" {
  value = var.namespace
}

output "web_deployment_name" {
  value = kubernetes_deployment_v1.web.metadata[0].name
}

output "worker_deployment_name" {
  value = kubernetes_deployment_v1.worker.metadata[0].name
}
