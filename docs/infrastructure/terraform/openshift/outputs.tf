# Java Maven Template - OpenShift Outputs

output "namespace" {
  description = "Namespace name"
  value       = kubernetes_namespace.app.metadata[0].name
}

output "deployment_name" {
  description = "Deployment name"
  value       = kubernetes_deployment.app.metadata[0].name
}

output "service_name" {
  description = "Service name"
  value       = kubernetes_service.app.metadata[0].name
}

output "route_name" {
  description = "Route name"
  value       = kubernetes_manifest.route.manifest.metadata.name
}

output "app_url" {
  description = "Application URL (route host)"
  value       = "http://${kubernetes_manifest.route.manifest.spec.host}"
}
