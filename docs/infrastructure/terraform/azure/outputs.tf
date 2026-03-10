# Java Maven Template - Azure Outputs

output "resource_group_name" {
  description = "Resource group name"
  value       = azurerm_resource_group.main.name
}

output "vnet_id" {
  description = "Virtual network ID"
  value       = azurerm_virtual_network.main.id
}

output "subnet_id" {
  description = "Subnet ID"
  value       = azurerm_subnet.main.id
}

output "vm_id" {
  description = "Virtual machine ID"
  value       = azurerm_linux_virtual_machine.app.id
}

output "public_ip_address" {
  description = "Public IP address"
  value       = azurerm_public_ip.app.ip_address
}

output "app_url" {
  description = "Application URL"
  value       = "http://${azurerm_public_ip.app.ip_address}:${var.app_port}"
}
