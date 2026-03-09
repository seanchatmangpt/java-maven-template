# Java Maven Template - IBM Cloud Outputs

output "vpc_id" {
  description = "VPC ID"
  value       = ibm_is_vpc.main.id
}

output "subnet_id" {
  description = "Subnet ID"
  value       = ibm_is_subnet.main.id
}

output "instance_id" {
  description = "Instance ID"
  value       = ibm_is_instance.app.id
}

output "floating_ip" {
  description = "Floating IP address"
  value       = ibm_is_floating_ip.app.address
}

output "app_url" {
  description = "Application URL"
  value       = "http://${ibm_is_floating_ip.app.address}:${var.app_port}"
}
