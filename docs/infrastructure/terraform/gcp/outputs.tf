# Java Maven Template - GCP Outputs

output "network_id" {
  description = "VPC network ID"
  value       = google_compute_network.main.id
}

output "subnet_id" {
  description = "Subnet ID"
  value       = google_compute_subnetwork.main.id
}

output "instance_id" {
  description = "Compute instance ID"
  value       = google_compute_instance.app.id
}

output "instance_ip" {
  description = "Instance IP address"
  value       = google_compute_address.app.address
}

output "app_url" {
  description = "Application URL"
  value       = "http://${google_compute_address.app.address}:${var.app_port}"
}
