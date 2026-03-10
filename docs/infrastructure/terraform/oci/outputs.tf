# Java Maven Template - OCI Outputs

output "vcn_id" {
  description = "VCN ID"
  value       = oci_core_vcn.main.id
}

output "subnet_id" {
  description = "Subnet ID"
  value       = oci_core_subnet.public.id
}

output "instance_id" {
  description = "Compute instance ID"
  value       = oci_core_instance.app.id
}

output "public_ip" {
  description = "Public IP address"
  value       = oci_core_instance.app.public_ip
}

output "app_url" {
  description = "Application URL"
  value       = "http://${oci_core_instance.app.public_ip}:${var.app_port}"
}
